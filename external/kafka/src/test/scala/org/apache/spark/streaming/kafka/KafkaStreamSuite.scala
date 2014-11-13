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

package org.apache.spark.streaming.kafka

import java.io.File
import java.net.InetSocketAddress
import java.util.Properties

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import kafka.admin.CreateTopicCommand
import kafka.common.{KafkaException, TopicAndPartition}
import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import kafka.serializer.{StringDecoder, StringEncoder}
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.utils.ZKStringSerializer
import org.I0Itec.zkclient.ZkClient
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.concurrent.Eventually

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.apache.spark.util.Utils

abstract class KafkaStreamSuiteBase extends FunSuite with BeforeAndAfter with Logging {
  import KafkaTestUtils._

  val sparkConf = new SparkConf()
    .setMaster("local[4]")
    .setAppName(this.getClass.getSimpleName)
  val batchDuration = Milliseconds(500)
  var ssc: StreamingContext = _
  
  var zkAddress: String = _
  var zkClient: ZkClient = _

  private val zkHost = "localhost"
  private val zkConnectionTimeout = 6000
  private val zkSessionTimeout = 6000
  private var zookeeper: EmbeddedZookeeper = _
  private var zkPort: Int = 0
  private var brokerPort = 9092
  private var brokerConf: KafkaConfig = _
  private var server: KafkaServer = _
  private var producer: Producer[String, String] = _

  def beforeFunction() {
    // Zookeeper server startup
    zookeeper = new EmbeddedZookeeper(s"$zkHost:$zkPort")
    // Get the actual zookeeper binding port
    zkPort = zookeeper.actualPort
    zkAddress = s"$zkHost:$zkPort"
    logInfo("==================== 0 ====================")

    zkClient = new ZkClient(zkAddress, zkSessionTimeout, zkConnectionTimeout,
      ZKStringSerializer)
    logInfo("==================== 1 ====================")

    // Kafka broker startup
    var bindSuccess: Boolean = false
    while(!bindSuccess) {
      try {
        val brokerProps = getBrokerConfig(brokerPort, zkAddress)
        brokerConf = new KafkaConfig(brokerProps)
        server = new KafkaServer(brokerConf)
        logInfo("==================== 2 ====================")
        server.startup()
        logInfo("==================== 3 ====================")
        bindSuccess = true
      } catch {
        case e: KafkaException =>
          if (e.getMessage != null && e.getMessage.contains("Socket server failed to bind to")) {
            brokerPort += 1
          }
        case e: Exception => throw new Exception("Kafka server create failed", e)
      }
    }

    Thread.sleep(2000)
    logInfo("==================== 4 ====================")
  }

  def afterFunction() {
    if (ssc != null) {
      ssc.stop()
      ssc = null
    }

    if (producer != null) {
      producer.close()
      producer = null
    }

    if (server != null) {
      server.shutdown()
      server = null
    }

    brokerConf.logDirs.foreach { f => Utils.deleteRecursively(new File(f)) }

    if (zkClient != null) {
      zkClient.close()
      zkClient = null
    }

    if (zookeeper != null) {
      zookeeper.shutdown()
      zookeeper = null
    }
  }

  private def createTestMessage(topic: String, sent: Map[String, Int])
    : Seq[KeyedMessage[String, String]] = {
    val messages = for ((s, freq) <- sent; i <- 0 until freq) yield {
      new KeyedMessage[String, String](topic, s)
    }
    messages.toSeq
  }

  def createTopic(topic: String) {
    CreateTopicCommand.createTopic(zkClient, topic, 1, 1, "0")
    logInfo("==================== 5 ====================")
    // wait until metadata is propagated
    waitUntilMetadataIsPropagated(Seq(server), topic, 0, 1000)
  }

  def produceAndSendMessage(topic: String, sent: Map[String, Int]) {
    val brokerAddr = brokerConf.hostName + ":" + brokerConf.port
    if (producer == null) {
      producer = new Producer[String, String](new ProducerConfig(getProducerConfig(brokerAddr)))
    }
    producer.send(createTestMessage(topic, sent): _*)
    logInfo("==================== 6 ====================")
  }
}

class KafkaStreamSuite extends KafkaStreamSuiteBase with Eventually {

  before { beforeFunction() }
  after { afterFunction() }

  test("Kafka input stream") {
    ssc = new StreamingContext(sparkConf, batchDuration)
    val topic = "topic1"
    val sent = Map("a" -> 5, "b" -> 3, "c" -> 10)
    createTopic(topic)
    produceAndSendMessage(topic, sent)

    val kafkaParams = Map("zookeeper.connect" -> zkAddress,
      "group.id" -> s"test-consumer-${Random.nextInt(10000)}",
      "auto.offset.reset" -> "smallest")

    val stream = KafkaUtils.createStream[String, String, StringDecoder, StringDecoder](
      ssc,
      kafkaParams,
      Map(topic -> 1),
      StorageLevel.MEMORY_ONLY)
    val result = new mutable.HashMap[String, Long]()
    stream.map { case (k, v) => v }
      .countByValue()
      .foreachRDD { r =>
        val ret = r.collect()
        ret.toMap.foreach { kv =>
          val count = result.getOrElseUpdate(kv._1, 0) + kv._2
          result.put(kv._1, count)
        }
      }
    ssc.start()
    eventually(timeout(3000 milliseconds), interval(100 milliseconds)) {
      assert(sent.size === result.size)
      sent.keys.foreach { k => assert(sent(k) === result(k).toInt) }
    }

    ssc.stop()
  }
}


object KafkaTestUtils {

  def getBrokerConfig(port: Int, zkConnect: String): Properties = {
    val props = new Properties()
    props.put("broker.id", "0")
    props.put("host.name", "localhost")
    props.put("port", port.toString)
    props.put("log.dir", Utils.createTempDir().getAbsolutePath)
    props.put("zookeeper.connect", zkConnect)
    props.put("log.flush.interval.messages", "1")
    props.put("replica.socket.timeout.ms", "1500")
    props
  }

  def getProducerConfig(brokerList: String): Properties = {
    val props = new Properties()
    props.put("metadata.broker.list", brokerList)
    props.put("serializer.class", classOf[StringEncoder].getName)
    props
  }

  def waitUntilTrue(condition: () => Boolean, waitTime: Long): Boolean = {
    val startTime = System.currentTimeMillis()
    while (true) {
      if (condition())
        return true
      if (System.currentTimeMillis() > startTime + waitTime)
        return false
      Thread.sleep(waitTime.min(100L))
    }
    // Should never go to here
    throw new RuntimeException("unexpected error")
  }

  def waitUntilMetadataIsPropagated(servers: Seq[KafkaServer], topic: String, partition: Int,
      timeout: Long) {
    assert(waitUntilTrue(() =>
      servers.foldLeft(true)(_ && _.apis.leaderCache.keySet.contains(
        TopicAndPartition(topic, partition))), timeout),
      s"Partition [$topic, $partition] metadata not propagated after timeout")
  }

  class EmbeddedZookeeper(val zkConnect: String) {
    val random = new Random()
    val snapshotDir = Utils.createTempDir()
    val logDir = Utils.createTempDir()

    val zookeeper = new ZooKeeperServer(snapshotDir, logDir, 500)
    val (ip, port) = {
      val splits = zkConnect.split(":")
      (splits(0), splits(1).toInt)
    }
    val factory = new NIOServerCnxnFactory()
    factory.configure(new InetSocketAddress(ip, port), 16)
    factory.startup(zookeeper)

    val actualPort = factory.getLocalPort

    def shutdown() {
      factory.shutdown()
      Utils.deleteRecursively(snapshotDir)
      Utils.deleteRecursively(logDir)
    }
  }
}
