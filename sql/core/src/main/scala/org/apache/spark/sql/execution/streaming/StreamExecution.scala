/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.Logging
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeMap}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan}
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.QueryExecution

/**
 * Manages the execution of a streaming Spark SQL query that is occurring in a separate thread.
 * Unlike a standard query, a streaming query executes repeatedly each time new data arrives at any
 * [[Source]] present in the query plan. Whenever new data arrives, a [[QueryExecution]] is created
 * and the results are committed transactionally to the given [[Sink]].
 */
class StreamExecution(
    val sqlContext: SQLContext,
    val name: String,
    private[sql] val logicalPlan: LogicalPlan,
    val sink: Sink) extends ContinuousQuery with Logging {

  /** An monitor used to wait/notify when batches complete. */
  private val awaitBatchLock = new Object
  private val awaitTerminationLock = new Object

  /** Minimum amount of time in between the start of each batch. */
  private val minBatchTime = 10

  /** Tracks how much data we have processed from each input source. */
  private[sql] val streamProgress = new StreamProgress

  /** All stream sources present the query plan. */
  private val sources =
    logicalPlan.collect { case s: StreamingRelation => s.source }

  // Start the execution at the current offsets stored in the sink. (i.e. avoid reprocessing data
  // that we have already processed).
  {
    sink.currentOffset match {
      case Some(c: CompositeOffset) =>
        val storedProgress = c.offsets
        val sources = logicalPlan collect {
          case StreamingRelation(source, _) => source
        }

        assert(sources.size == storedProgress.size)
        sources.zip(storedProgress).foreach { case (source, offset) =>
          offset.foreach(streamProgress.update(source, _))
        }
      case None => // We are starting this stream for the first time.
      case _ => throw new IllegalArgumentException("Expected composite offset from sink")
    }
  }

  logInfo(s"Stream running at $streamProgress")

  /** When false, signals to the microBatchThread that it should stop running. */
  @volatile
  private var active = true

  @volatile
  private[sql] var lastExecution: QueryExecution = null

  @volatile
  private[sql] var streamDeathCause: QueryException = null

  /** The thread that runs the micro-batches of this stream. */
  private[sql] val microBatchThread = new Thread("stream execution thread") {
    override def run(): Unit = {
      try {
        SQLContext.setActive(sqlContext)
        while (active) {
          attemptBatch()
          Thread.sleep(minBatchTime) // TODO: Could be tighter
        }
      } catch {
        case _: InterruptedException if active == false => // interrupted by stop()
        case t: Throwable =>
          streamDeathCause = new QueryException(
            s"Query terminated with exception", t, Some(streamProgress.toOffset))
      } finally {
        active = false
        sqlContext.streams.notifyQueryTermination(StreamExecution.this)
        awaitTerminationLock.synchronized {
          awaitTerminationLock.notifyAll()
        }
      }
    }
  }
  microBatchThread.setDaemon(true)
  microBatchThread.start()

  @volatile
  private var batchRun = false

  /** Whether the query is currently active or not */
  override def isActive: Boolean = active

  /** Returns current status of all the sources. */
  override def sourceStatuses: Array[SourceStatus] = {
    sources.map(s => new SourceStatus(s.toString, streamProgress.get(s))).toArray
  }

  /** Returns current status of the sink. */
  override def sinkStatus: SinkStatus = new SinkStatus(sink.toString, sink.currentOffset)

  /** Returns the [[QueryException]] if the query was terminated by an exception. */
  override def exception: Option[QueryException] = Option(streamDeathCause)

  /**
   * Checks to see if any new data is present in any of the sources.  When new data is available,
   * a batch is executed and passed to the sink, updating the currentOffsets.
   */
  private def attemptBatch(): Unit = {
    val startTime = System.nanoTime()

    // A list of offsets that need to be updated if this batch is successful.
    // Populated while walking the tree.
    val newOffsets = new ArrayBuffer[(Source, Offset)]
    // A list of attributes that will need to be updated.
    var replacements = new ArrayBuffer[(Attribute, Attribute)]
    // Replace sources in the logical plan with data that has arrived since the last batch.
    val withNewSources = logicalPlan transform {
      case StreamingRelation(source, output) =>
        val prevOffset = streamProgress.get(source)
        val newBatch = source.getNextBatch(prevOffset)

        newBatch.map { batch =>
          newOffsets += ((source, batch.end))
          val newPlan = batch.data.logicalPlan

          assert(output.size == newPlan.output.size)
          replacements ++= output.zip(newPlan.output)
          newPlan
        }.getOrElse {
          LocalRelation(output)
        }
    }

    // Rewire the plan to use the new attributes that were returned by the source.
    val replacementMap = AttributeMap(replacements)
    val newPlan = withNewSources transformAllExpressions {
      case a: Attribute if replacementMap.contains(a) => replacementMap(a)
    }

    if (newOffsets.nonEmpty) {
      val optimizerStart = System.nanoTime()

      lastExecution = new QueryExecution(sqlContext, newPlan)
      val executedPlan = lastExecution.executedPlan
      val optimizerTime = (System.nanoTime() - optimizerStart).toDouble / 1000000
      logDebug(s"Optimized batch in ${optimizerTime}ms")

      streamProgress.synchronized {
        // Update the offsets and calculate a new composite offset
        newOffsets.foreach(streamProgress.update)
        val newStreamProgress = logicalPlan.collect {
          case StreamingRelation(source, _) => streamProgress.get(source)
        }
        val batchOffset = CompositeOffset(newStreamProgress)

        // Construct the batch and send it to the sink.
        val nextBatch = new Batch(batchOffset, new DataFrame(sqlContext, newPlan))
        sink.addBatch(nextBatch)
      }

      active = true
      awaitBatchLock.synchronized {
        // Wake up any threads that are waiting for the stream to progress.
        awaitBatchLock.notifyAll()
      }

      val batchTime = (System.nanoTime() - startTime).toDouble / 1000000
      logInfo(s"Compete up to $newOffsets in ${batchTime}ms")
    }

    logDebug(s"Waiting for data, current: $streamProgress")
  }

  /**
   * Signals to the thread executing micro-batches that it should stop running after the next
   * batch. This method blocks until the thread stops running.
   */
  override def stop(): Unit = {
    active = false
    if (microBatchThread.isAlive) {
      microBatchThread.interrupt()
      microBatchThread.join()
    }
  }

  /**
   * Blocks the current thread until processing for data from the given `source` has reached at
   * least the given `Offset`. This method is indented for use primarily when writing tests.
   */
  def awaitOffset(source: Source, newOffset: Offset): Unit = {
    def notDone = streamProgress.synchronized {
      !streamProgress.contains(source) || streamProgress(source) < newOffset
    }

    while (notDone) {
      logInfo(s"Waiting until $newOffset at $source")
      awaitBatchLock.synchronized { awaitBatchLock.wait(100) }
    }
    logDebug(s"Unblocked at $newOffset for $source")
  }

  override def awaitTermination(): Unit = {
    awaitTerminationLock.synchronized {
      while (active) {
        awaitTerminationLock.wait(10)
      }
      if (streamDeathCause != null) {
        throw streamDeathCause
      }
    }
  }

  override def awaitTermination(timeoutMs: Long): Boolean = {
    require(timeoutMs > 0, "Timeout has to be positive")

    val endTime = System.currentTimeMillis + timeoutMs
    def timeLeft = math.max(endTime - System.currentTimeMillis, 0)

    awaitTerminationLock.synchronized {
      while (active && timeLeft > 0) {
        awaitTerminationLock.wait(10)
      }
      if (streamDeathCause != null) {
        throw streamDeathCause
      } else {
        !isActive
      }
    }
  }

  override def toString: String =
    s"""
       |=== Streaming Query ===
       |CurrentOffsets: $streamProgress
       |Thread State: ${microBatchThread.getState}
       |${if (streamDeathCause != null) stackTraceToString(streamDeathCause) else ""}
       |
       |$logicalPlan
     """.stripMargin
}

private[sql] object StreamExecution {
  private val nextId = new AtomicInteger()

  def nextName = s"query-${nextId.getAndIncrement}"
}