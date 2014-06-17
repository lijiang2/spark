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
package org.apache.spark.flume.sink

import java.nio.ByteBuffer
import java.util
import java.util.concurrent.{TimeUnit, CountDownLatch, Callable}

import scala.util.control.Breaks

import org.apache.flume.{Transaction, Channel}
import org.apache.spark.flume.{SparkSinkEvent, ErrorEventBatch, EventBatch}
import org.slf4j.LoggerFactory


// Flume forces transactions to be thread-local (horrible, I know!)
// So the sink basically spawns a new thread to pull the events out within a transaction.
// The thread fills in the event batch object that is set before the thread is scheduled.
// After filling it in, the thread waits on a condition - which is released only
// when the success message comes back for the specific sequence number for that event batch.
/**
 * This class represents a transaction on the Flume channel. This class runs a separate thread
 * which owns the transaction. The thread is blocked until the success call for that transaction
 * comes back with an ACK or NACK.
 * @param channel The channel from which to pull events
 * @param seqNum The sequence number to use for the transaction. Must be unique
 * @param maxBatchSize The maximum number of events to process per batch
 * @param transactionTimeout Time in seconds after which a transaction must be rolled back
 *                           without waiting for an ACK from Spark
 * @param parent The parent [[SparkAvroCallbackHandler]] instance, for reporting timeouts
 */
private class TransactionProcessor(val channel: Channel, val seqNum: String,
  var maxBatchSize: Int, val transactionTimeout: Int, val backOffInterval: Int,
  val parent: SparkAvroCallbackHandler) extends Callable[Void] {

  private val LOG = LoggerFactory.getLogger(classOf[TransactionProcessor])

  // If a real batch is not returned, we always have to return an error batch.
  @volatile private var eventBatch: EventBatch = new ErrorEventBatch("Unknown Error")

  // Synchronization primitives
  val batchGeneratedLatch = new CountDownLatch(1)
  val batchAckLatch = new CountDownLatch(1)

  // Sanity check to ensure we don't loop like crazy
  val totalAttemptsToRemoveFromChannel = Int.MaxValue / 2

  // OK to use volatile, since the change would only make this true (otherwise it will be
  // changed to false - we never apply a negation operation to this) - which means the transaction
  // succeeded.
  @volatile private var batchSuccess = false

  // The transaction that this processor would handle
  var txOpt: Option[Transaction] = None

  /**
   * Get an event batch from the channel. This method will block until a batch of events is
   * available from the channel. If no events are available after a large number of attempts of
   * polling the channel, this method will return [[ErrorEventBatch]].
   *
   * @return An [[EventBatch]] instance with sequence number set to seqNum, filled with a
   *         maximum of maxBatchSize events
   */
  def getEventBatch: EventBatch = {
    batchGeneratedLatch.await()
    eventBatch
  }

  /**
   * This method is to be called by the sink when it receives an ACK or NACK from Spark. This
   * method is a no-op if it is called after transactionTimeout has expired since
   * getEventBatch returned a batch of events.
   * @param success True if an ACK was received and the transaction should be committed, else false.
   */
  def batchProcessed(success: Boolean) {
    if (LOG.isDebugEnabled) {
      LOG.debug("Batch processed for sequence number: " + seqNum)
    }
    batchSuccess = success
    batchAckLatch.countDown()
  }

  /**
   * Populates events into the event batch. If the batch cannot be populated,
   * this method will not set the event batch which will stay [[ErrorEventBatch]]
   */
  private def populateEvents() {
    try {
      txOpt = Option(channel.getTransaction)
      if(txOpt.isEmpty) {
        assert(eventBatch.isInstanceOf[ErrorEventBatch])
        eventBatch.asInstanceOf[ErrorEventBatch].message = "Something went wrong. Channel was " +
          "unable to create a transaction!"
        eventBatch
      }
      txOpt.map(tx => {
        tx.begin()
        val events = new util.ArrayList[SparkSinkEvent](maxBatchSize)
        val loop = new Breaks
        var gotEventsInThisTxn = false
        var loopCounter: Int = 0
        loop.breakable {
          while (events.size() < maxBatchSize
            && loopCounter < totalAttemptsToRemoveFromChannel) {
            loopCounter += 1
            Option(channel.take()) match {
              case Some(event) =>
                events.add(new SparkSinkEvent(toCharSequenceMap(event.getHeaders),
                  ByteBuffer.wrap(event.getBody)))
                gotEventsInThisTxn = true
              case None =>
                if (!gotEventsInThisTxn) {
                  TimeUnit.MILLISECONDS.sleep(backOffInterval)
                } else {
                  loop.break()
                }
            }
          }
        }
        if (!gotEventsInThisTxn) {
          val msg = "Tried several times, " +
            "but did not get any events from the channel!"
          LOG.warn(msg)
          eventBatch.asInstanceOf[ErrorEventBatch].message = msg
        } else {
          // At this point, the events are available, so fill them into the event batch
          eventBatch = new EventBatch(seqNum, events)
        }
      })
    } catch {
      case e: Exception =>
        LOG.error("Error while processing transaction.", e)
        eventBatch.asInstanceOf[ErrorEventBatch].message = e.getMessage
        try {
          txOpt.map(tx => {
            rollbackAndClose(tx, close = true)
          })
        } finally {
          txOpt = None
        }
    } finally {
      batchGeneratedLatch.countDown()
    }
  }

  /**
   * Waits for upto transactionTimeout seconds for an ACK. If an ACK comes in
   * this method commits the transaction with the channel. If the ACK does not come in within
   * that time or a NACK comes in, this method rolls back the transaction.
   */
  private def processAckOrNack() {
    batchAckLatch.await(transactionTimeout, TimeUnit.SECONDS)
    txOpt.map(tx => {
      if (batchSuccess) {
        try {
          tx.commit()
        } catch {
          case e: Exception =>
            LOG.warn("Error while attempting to commit transaction. Transaction will be rolled " +
              "back", e)
            rollbackAndClose(tx, close = false) // tx will be closed later anyway
        } finally {
          tx.close()
        }
      } else {
        LOG.warn("Spark could not commit transaction, NACK received. Rolling back transaction.")
        rollbackAndClose(tx, close = true)
        // This might have been due to timeout or a NACK. Either way the following call does not
        // cause issues. This is required to ensure the TransactionProcessor instance is not leaked
        parent.removeAndGetProcessor(seqNum)
      }
    })
  }

  /**
   * Helper method to rollback and optionally close a transaction
   * @param tx The transaction to rollback
   * @param close Whether the transaction should be closed or not after rolling back
   */
  private def rollbackAndClose(tx: Transaction, close: Boolean) {
    try {
      LOG.warn("Spark was unable to successfully process the events. Transaction is being " +
        "rolled back.")
      tx.rollback()
    } catch {
      case e: Exception =>
        LOG.error("Error rolling back transaction. Rollback may have failed!", e)
    } finally {
      if (close) {
        tx.close()
      }
    }
  }

  /**
   * Helper method to convert a Map[String, String] to Map[CharSequence, CharSequence]
   * @param inMap The map to be converted
   * @return The converted map
   */
  private def toCharSequenceMap(inMap: java.util.Map[String, String]): java.util.Map[CharSequence,
    CharSequence] = {
    val charSeqMap = new util.HashMap[CharSequence, CharSequence](inMap.size())
    charSeqMap.putAll(inMap)
    charSeqMap
  }

  override def call(): Void = {
    populateEvents()
    processAckOrNack()
    null
  }
}
