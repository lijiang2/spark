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
package org.apache.spark.streaming.rdd

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ArrayBuffer
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FunSuite}

import com.google.common.io.Files
import org.apache.hadoop.conf.Configuration

import org.apache.spark.storage.{BlockManager, BlockId, StorageLevel, StreamBlockId}
import org.apache.spark.streaming.util.{WriteAheadLogFileSegment, WriteAheadLogWriter}

class HDFSBackedBlockRDDSuite extends FunSuite with BeforeAndAfter with BeforeAndAfterAll {
  val conf = new SparkConf()
    .setMaster("local[2]")
    .setAppName(this.getClass.getSimpleName)
  val hadoopConf = new Configuration()
  // Since the same BM is reused in all tests, use an atomic int to generate ids
  val idGenerator = new AtomicInteger(0)
  
  var sparkContext: SparkContext = null
  var blockManager: BlockManager = null
  var file: File = null
  var dir: File = null

  before {
    blockManager = sparkContext.env.blockManager
    dir = Files.createTempDir()
    file = new File(dir, "BlockManagerWrite")
  }

  after {
    file.delete()
    dir.delete()
  }

  override def beforeAll(): Unit = {
    sparkContext = new SparkContext(conf)
  }

  override def afterAll(): Unit = {
    // Copied from LocalSparkContext which can't be imported since spark-core test-jar does not
    // get imported properly by sbt even if it is created.
    sparkContext.stop()
    System.clearProperty("spark.driver.port")
  }

  test("Data available in BM and HDFS") {
    testHDFSBackedRDD(5, 5, 20, 5)
  }

  test("Data available in in BM but not in HDFS") {
    testHDFSBackedRDD(5, 0, 20, 5)
  }

  test("Data available in in HDFS and not in BM") {
    testHDFSBackedRDD(0, 5, 20, 5)
  }

  test("Data partially available in BM, and the rest in HDFS") {
    testHDFSBackedRDD(3, 2, 20, 5)
  }

  /**
   * Write a bunch of events into the HDFS Block RDD. Put a part of all of them to the
   * BlockManager, so all reads need not happen from HDFS.
   * @param total Total number of Strings to write
   * @param blockCount Number of blocks to write (therefore, total # of events per block =
   *                   total/blockCount
   */
  private def testHDFSBackedRDD(
      writeToBMCount: Int,
      writeToHDFSCount: Int,
      total: Int,
      blockCount: Int
    ) {
    val countPerBlock = total / blockCount
    val blockIds = (0 until blockCount).map { i =>
        StreamBlockId(idGenerator.incrementAndGet(), idGenerator.incrementAndGet())
    }

    val writtenStrings = generateData(total, countPerBlock)

    if (writeToBMCount != 0) {
      (0 until writeToBMCount).foreach { i =>
        blockManager
          .putIterator(blockIds(i), writtenStrings(i).iterator, StorageLevel.MEMORY_ONLY_SER)
      }
    }

    val segments = {
      if (writeToHDFSCount != 0) {
        // Generate some fake segments for the blocks in BM so the RDD does not complain
        generateFakeSegments(writeToBMCount) ++
          writeDataToHDFS(writtenStrings.slice(writeToBMCount, blockCount),
            blockIds.slice(writeToBMCount, blockCount))
      } else {
        generateFakeSegments(blockCount)
      }
    }

    val rdd = new HDFSBackedBlockRDD[String](sparkContext, hadoopConf, blockIds.toArray,
      segments.toArray, false, StorageLevel.MEMORY_ONLY)

    val dataFromRDD = rdd.collect()
    // verify each partition is equal to the data pulled out
    assert(writtenStrings.flatten === dataFromRDD)
  }

  /**
   * Write data to HDFS and get a list of Seq of Seqs in which each Seq represents the data that
   * went into one block.
   * @param count Number of Strings to write
   * @param countPerBlock Number of Strings per block
   * @return Seq of Seqs, each of these Seqs is one block
   */
  private def generateData(
      count: Int,
      countPerBlock: Int
    ): Seq[Seq[String]] = {
    val strings = (0 until count).map { _ => scala.util.Random.nextString(50)}
    strings.grouped(countPerBlock).toSeq
  }

  private def writeDataToHDFS(
      blockData: Seq[Seq[String]],
      blockIds: Seq[BlockId]
    ): Seq[WriteAheadLogFileSegment] = {
    assert(blockData.size === blockIds.size)
    val segments = new ArrayBuffer[WriteAheadLogFileSegment]()
    val writer = new WriteAheadLogWriter(file.toString, hadoopConf)
    blockData.zip(blockIds).foreach {
      case (data, id) =>
        segments += writer.write(blockManager.dataSerialize(id, data.iterator))
    }
    writer.close()
    segments
  }

  private def generateFakeSegments(count: Int): Seq[WriteAheadLogFileSegment] = {
    (0 until count).map { _ => new WriteAheadLogFileSegment("random", 0l, 0) }
  }
}
