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

package org.apache.spark.streaming.kinesis

import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream
import org.scalatest.time.SpanSugar._

import org.apache.spark.SparkEnv
import org.apache.spark.sql.{AnalysisException, StreamTest}
import org.apache.spark.sql.execution.streaming.{Offset, Source, StreamingRelation}
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.storage.StreamBlockId

class KinesisSourceTest extends StreamTest with SharedSQLContext {

  case class AddKinesisData(
      testUtils: KPLBasedKinesisTestUtils,
      kinesisSource: KinesisSource,
      data: Seq[Int]) extends AddData {

    override def addData(): Offset = {
      testUtils.pushData(data, false)
      val shardIdToSeqNums = testUtils.getLatestSeqNumsOfShards().map { case (shardId, seqNum) =>
        (Shard(testUtils.streamName, shardId), seqNum)
      }
      KinesisSourceOffset(shardIdToSeqNums)
    }

    override def source: Source = kinesisSource
  }
}

class KinesisSourceSuite extends KinesisSourceTest with KinesisFunSuite {

  import testImplicits._

  testIfEnabled("basic receiving") {
    var streamBlocksInLastBatch: Seq[StreamBlockId] = Seq.empty

    def assertStreamBlocks: Boolean = {
      // Assume the test runs in local mode and there is only one BlockManager.
      val streamBlocks =
        SparkEnv.get.blockManager.getMatchingBlockIds(_.isInstanceOf[StreamBlockId])
      val cleaned = streamBlocks.intersect(streamBlocksInLastBatch).isEmpty
      streamBlocksInLastBatch = streamBlocks.map(_.asInstanceOf[StreamBlockId])
      cleaned
    }

    val testUtils = new KPLBasedKinesisTestUtils
    testUtils.createStream()
    try {
      val kinesisSource = new KinesisSource(
        sqlContext,
        testUtils.regionName,
        testUtils.endpointUrl,
        Set(testUtils.streamName),
        InitialPositionInStream.TRIM_HORIZON)
      val mapped =
        kinesisSource.toDS[Array[Byte]]().map((bytes: Array[Byte]) => new String(bytes).toInt + 1)
      val testData = 1 to 10
      testStream(mapped)(
        AddKinesisData(testUtils, kinesisSource, testData),
        CheckAnswer((1 to 10).map(_ + 1): _*),
        Assert(assertStreamBlocks, "Old stream blocks should be cleaned"),
        AddKinesisData(testUtils, kinesisSource, 11 to 20),
        CheckAnswer((1 to 20).map(_ + 1): _*),
        Assert(assertStreamBlocks, "Old stream blocks should be cleaned"),
        AddKinesisData(testUtils, kinesisSource, 21 to 30),
        CheckAnswer((1 to 30).map(_ + 1): _*),
        Assert(assertStreamBlocks, "Old stream blocks should be cleaned")
      )
    } finally {
      testUtils.deleteStream()
    }
  }

  testIfEnabled("failover") {
    val testUtils = new KPLBasedKinesisTestUtils
    testUtils.createStream()
    try {
      val kinesisSource = new KinesisSource(
        sqlContext,
        testUtils.regionName,
        testUtils.endpointUrl,
        Set(testUtils.streamName),
        InitialPositionInStream.TRIM_HORIZON)
      val mapped =
        kinesisSource.toDS[Array[Byte]]().map((bytes: Array[Byte]) => new String(bytes).toInt + 1)
      testStream(mapped)(
        AddKinesisData(testUtils, kinesisSource, 1 to 10),
        CheckAnswer((1 to 10).map(_ + 1): _*),
        StopStream,
        AddKinesisData(testUtils, kinesisSource, 11 to 20),
        StartStream,
        CheckAnswer((1 to 20).map(_ + 1): _*),
        AddKinesisData(testUtils, kinesisSource, 21 to 30),
        CheckAnswer((1 to 30).map(_ + 1): _*)
      )
    } finally {
      testUtils.deleteStream()
    }
  }

  testIfEnabled("DataFrameReader") {
    val testUtils = new KPLBasedKinesisTestUtils
    testUtils.createStream()
    try {
      val df = sqlContext.read
        .option("regionName", testUtils.regionName)
        .option("endpointUrl", testUtils.endpointUrl)
        .option("streamNames", testUtils.streamName)
        .option("initialPosInStream", "TRIM_HORIZON")
        .kinesis().stream()

      val sources = df.queryExecution.analyzed
        .collect {
          case StreamingRelation(s: KinesisSource, _) => s
        }
      assert(sources.size === 1)
    } finally {
      testUtils.deleteStream()
    }
  }

  testIfEnabled("call kinesis when not using stream") {
    val e = intercept[AnalysisException] {
      sqlContext.read.kinesis().load()
    }
    assert(e.getMessage === "org.apache.spark.streaming.kinesis.DefaultSource is " +
      "neither a RelationProvider nor a FSBasedRelationProvider.;")
  }
}

class KinesisSourceStressTestSuite extends KinesisSourceTest with KinesisFunSuite {

  import testImplicits._

  override val streamingTimeout = 60.seconds

  test("kinesis source stress test") {
    val testUtils = new KPLBasedKinesisTestUtils
    testUtils.createStream()
    try {
      val kinesisSource = new KinesisSource(
        sqlContext,
        testUtils.regionName,
        testUtils.endpointUrl,
        Set(testUtils.streamName),
        InitialPositionInStream.TRIM_HORIZON)

      val ds = kinesisSource.toDS[String]().map(_.toInt + 1)
      runStressTest(ds, data => {
        AddKinesisData(testUtils, kinesisSource, data)
      })
    } finally {
      testUtils.deleteStream()
    }
  }
}
