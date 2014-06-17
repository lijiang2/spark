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

import java.net.InetSocketAddress
import java.util.concurrent._

import org.apache.avro.ipc.NettyServer
import org.apache.avro.ipc.specific.SpecificResponder
import org.apache.flume.Sink.Status
import org.apache.flume.conf.{Configurable, ConfigurationException}
import org.apache.flume.sink.AbstractSink
import org.apache.flume.Context
import org.slf4j.LoggerFactory

import org.apache.spark.flume.SparkFlumeProtocol

/**
 * A sink that uses Avro RPC to run a server that can be polled by Spark's
 * FlumePollingInputDStream. This sink has the following configuration parameters:
 *
 * hostname - The hostname to bind to. Default: 0.0.0.0
 * port - The port to bind to. (No default - mandatory)
 * timeout - Time in seconds after which a transaction is rolled back,
 * if an ACK is not received from Spark within that time
 * threads - Number of threads to use to receive requests from Spark (Default: 10)
 *
 */
// Flume forces transactions to be thread-local. So each transaction *must* be committed, or
// rolled back from the thread it was originally created in. So each getEvents call from Spark
// creates a TransactionProcessor which runs in a new thread, in which the transaction is created
// and events are pulled off the channel. Once the events are sent to spark,
// that thread is blocked and the TransactionProcessor is saved in a map,
// until an ACK or NACK comes back or the transaction times out (after the specified timeout).
// When the response comes, the TransactionProcessor is retrieved and then unblocked,
// at which point the transaction is committed or rolled back.
class SparkSink extends AbstractSink with Configurable {

  private val LOG = LoggerFactory.getLogger(classOf[SparkSink])
  // Size of the pool to use for holding transaction processors.
  private var poolSize: Integer = SparkSinkConfig.DEFAULT_THREADS

  // Timeout for each transaction. If spark does not respond in this much time,
  // rollback the transaction
  private var transactionTimeout = SparkSinkConfig.DEFAULT_TRANSACTION_TIMEOUT

  // Address info to bind on
  private var hostname: String = SparkSinkConfig.DEFAULT_HOSTNAME
  private var port: Int = 0

  private var backOffInterval: Int = 200

  // Handle to the server
  private var serverOpt: Option[NettyServer] = None

  // The handler that handles the callback from Avro
  private var handler: Option[SparkAvroCallbackHandler] = None

  // Latch that blocks off the Flume framework from wasting 1 thread.
  private val blockingLatch = new CountDownLatch(1)

  override def start() {
    LOG.info("Starting Spark Sink: " + getName + " on port: " + port + " and interface: " +
      hostname + " with " + "pool size: " + poolSize + " and transaction timeout: " +
      transactionTimeout + ".")
    handler = Option(new SparkAvroCallbackHandler(poolSize, getChannel, transactionTimeout,
      backOffInterval))
    val responder = new SpecificResponder(classOf[SparkFlumeProtocol], handler.get)
    // Using the constructor that takes specific thread-pools requires bringing in netty
    // dependencies which are being excluded in the build. In practice,
    // Netty dependencies are already available on the JVM as Flume would have pulled them in.
    serverOpt = Option(new NettyServer(responder, new InetSocketAddress(hostname, port)))
    serverOpt.map(server => {
      LOG.info("Starting Avro server for sink: " + getName)
      server.start()
    })
    super.start()
  }

  override def stop() {
    LOG.info("Stopping Spark Sink: " + getName)
    handler.map(callbackHandler => {
      callbackHandler.shutdown()
    })
    serverOpt.map(server => {
      LOG.info("Stopping Avro Server for sink: " + getName)
      server.close()
      server.join()
    })
    blockingLatch.countDown()
    super.stop()
  }

  override def configure(ctx: Context) {
    import SparkSinkConfig._
    hostname = ctx.getString(CONF_HOSTNAME, DEFAULT_HOSTNAME)
    port = Option(ctx.getInteger(CONF_PORT)).
      getOrElse(throw new ConfigurationException("The port to bind to must be specified"))
    poolSize = ctx.getInteger(THREADS, DEFAULT_THREADS)
    transactionTimeout = ctx.getInteger(CONF_TRANSACTION_TIMEOUT, DEFAULT_TRANSACTION_TIMEOUT)
    backOffInterval = ctx.getInteger(CONF_BACKOFF_INTERVAL, DEFAULT_BACKOFF_INTERVAL)
  }

  override def process(): Status = {
    // This method is called in a loop by the Flume framework - block it until the sink is
    // stopped to save CPU resources. The sink runner will interrupt this thread when the sink is
    // being shut down.
    blockingLatch.await()
    Status.BACKOFF
  }
}

/**
 * Configuration parameters and their defaults.
 */
object SparkSinkConfig {
  val THREADS = "threads"
  val DEFAULT_THREADS = 10

  val CONF_TRANSACTION_TIMEOUT = "timeout"
  val DEFAULT_TRANSACTION_TIMEOUT = 60

  val CONF_HOSTNAME = "hostname"
  val DEFAULT_HOSTNAME = "0.0.0.0"

  val CONF_PORT = "port"

  val CONF_BACKOFF_INTERVAL = "backoffInterval"
  val DEFAULT_BACKOFF_INTERVAL = 200
}
