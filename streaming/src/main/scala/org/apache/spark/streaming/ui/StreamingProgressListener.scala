package org.apache.spark.streaming.ui

import org.apache.spark.streaming.{Time, StreamingContext}
import org.apache.spark.streaming.scheduler._
import scala.collection.mutable.{Queue, HashMap}
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStarted
import org.apache.spark.streaming.scheduler.StreamingListenerBatchStarted
import org.apache.spark.streaming.scheduler.BatchInfo
import org.apache.spark.streaming.scheduler.ReceiverInfo
import org.apache.spark.streaming.scheduler.StreamingListenerBatchSubmitted
import org.apache.spark.util.Distribution


private[ui] class StreamingProgressListener(ssc: StreamingContext) extends StreamingListener {

  private val waitingBatchInfos = new HashMap[Time, BatchInfo]
  private val runningBatchInfos = new HashMap[Time, BatchInfo]
  private val completedaBatchInfos = new Queue[BatchInfo]
  private val batchInfoLimit = ssc.conf.getInt("spark.steaming.ui.maxBatches", 100)
  private var totalCompletedBatches = 0L
  private val receiverInfos = new HashMap[Int, ReceiverInfo]

  val batchDuration = ssc.graph.batchDuration.milliseconds

  override def onReceiverStarted(receiverStarted: StreamingListenerReceiverStarted) = {
    synchronized {
      receiverInfos.put(receiverStarted.receiverInfo.streamId, receiverStarted.receiverInfo)
    }
  }

  override def onBatchSubmitted(batchSubmitted: StreamingListenerBatchSubmitted) = synchronized {
    runningBatchInfos(batchSubmitted.batchInfo.batchTime) = batchSubmitted.batchInfo
  }

  override def onBatchStarted(batchStarted: StreamingListenerBatchStarted) = synchronized {
    runningBatchInfos(batchStarted.batchInfo.batchTime) = batchStarted.batchInfo
    waitingBatchInfos.remove(batchStarted.batchInfo.batchTime)
  }

  override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted) = synchronized {
    waitingBatchInfos.remove(batchCompleted.batchInfo.batchTime)
    runningBatchInfos.remove(batchCompleted.batchInfo.batchTime)
    completedaBatchInfos.enqueue(batchCompleted.batchInfo)
    if (completedaBatchInfos.size > batchInfoLimit) completedaBatchInfos.dequeue()
    totalCompletedBatches += 1L
  }

  def numNetworkReceivers = synchronized {
    ssc.graph.getNetworkInputStreams().size
  }

  def numTotalCompletedBatches: Long = synchronized {
    totalCompletedBatches
  }

  def numUnprocessedBatches: Long = synchronized {
    waitingBatchInfos.size + runningBatchInfos.size
  }

  def waitingBatches: Seq[BatchInfo] = synchronized {
    waitingBatchInfos.values.toSeq
  }

  def runningBatches: Seq[BatchInfo] = synchronized {
    runningBatchInfos.values.toSeq
  }

  def completedBatches: Seq[BatchInfo] = synchronized {
    completedaBatchInfos.toSeq
  }

  def processingDelayDistribution: Option[Distribution] = synchronized {
    extractDistribution(_.processingDelay)
  }

  def schedulingDelayDistribution: Option[Distribution] = synchronized {
    extractDistribution(_.schedulingDelay)
  }

  def totalDelayDistribution: Option[Distribution] = synchronized {
    extractDistribution(_.totalDelay)
  }

  def receivedRecordsDistributions: Map[Int, Option[Distribution]] = synchronized {
    val latestBatchInfos = allBatches.reverse.take(batchInfoLimit)
    val latestBlockInfos = latestBatchInfos.map(_.receivedBlockInfo)
    (0 until numNetworkReceivers).map { receiverId =>
      val blockInfoOfParticularReceiver = latestBlockInfos.map { batchInfo =>
        batchInfo.get(receiverId).getOrElse(Array.empty)
      }
      val recordsOfParticularReceiver = blockInfoOfParticularReceiver.map { blockInfo =>
      // calculate records per second for each batch
        blockInfo.map(_.numRecords).sum.toDouble * 1000 / batchDuration
      }
      val distributionOption = Distribution(recordsOfParticularReceiver)
      (receiverId, distributionOption)
    }.toMap
  }

  def lastReceivedBatchRecords: Map[Int, Long] = {
    val lastReceivedBlockInfoOption = lastReceivedBatch.map(_.receivedBlockInfo)
    lastReceivedBlockInfoOption.map { lastReceivedBlockInfo =>
      (0 until numNetworkReceivers).map { receiverId =>
        (receiverId, lastReceivedBlockInfo(receiverId).map(_.numRecords).sum)
      }.toMap
    }.getOrElse {
      (0 until numNetworkReceivers).map(receiverId => (receiverId, 0L)).toMap
    }
  }

  def receiverInfo(receiverId: Int): Option[ReceiverInfo] = {
    receiverInfos.get(receiverId)
  }

  def lastCompletedBatch: Option[BatchInfo] = {
    completedaBatchInfos.sortBy(_.batchTime)(Time.ordering).lastOption
  }

  def lastReceivedBatch: Option[BatchInfo] = {
    allBatches.lastOption
  }

  private def allBatches: Seq[BatchInfo] = synchronized {
    (waitingBatchInfos.values.toSeq ++
      runningBatchInfos.values.toSeq ++ completedaBatchInfos).sortBy(_.batchTime)(Time.ordering)
  }

  private def extractDistribution(getMetric: BatchInfo => Option[Long]): Option[Distribution] = {
    Distribution(completedaBatchInfos.flatMap(getMetric(_)).map(_.toDouble))
  }
}
