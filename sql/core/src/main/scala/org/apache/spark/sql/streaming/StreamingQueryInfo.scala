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

package org.apache.spark.sql.streaming

import org.apache.spark.annotation.Experimental

/**
 * :: Experimental ::
 * A class used to report information about the progress of a [[StreamingQuery]].
 *
 * @param name The [[StreamingQuery]] name. This name is unique across all active queries.
 * @param id The [[StreamingQuery]] id. This id is unique across
  *          all queries that have been started in the current process.
 * @param sourceStatuses The current statuses of the [[StreamingQuery]]'s sources.
 * @param sinkStatus The current status of the [[StreamingQuery]]'s sink.
 */
@Experimental
case class StreamingQueryInfo private(
  val name: String,
  val id: Long,
  val timestamp: Long,
  val inputRate: Double,
  val processingRate: Double,
  val outputRate: Double,
  val latencyMs: Option[Double],
  val sourceStatuses: Seq[SourceStatus],
  val sinkStatus: SinkStatus,
  val triggerStatus: Map[String, String]
)

/** Companion object, primarily for creating StreamingQueryInfo instances internally */
private[sql] object StreamingQueryInfo
