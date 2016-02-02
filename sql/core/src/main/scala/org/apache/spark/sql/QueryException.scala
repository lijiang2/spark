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

package org.apache.spark.sql

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.execution.streaming.Offset

/**
 * :: Experimental ::
 * Exception that stopped a [[ContinuousQuery]].
 *
 * @param message     Message of this exception
 * @param cause       Internal cause of this exception
 * @param startOffset Starting offset (if known) of the range of data in which exception occurred
 * @param endOffset   Ending offset (if known) of the range of data in exception occurred
 * @since 2.0.0
 */
@Experimental
class QueryException private[sql](
    val message: String,
    val cause: Throwable,
    val startOffset: Option[Offset] = None,
    val endOffset: Option[Offset] = None
  ) extends Exception(message, cause) {

  /** Time when the exception occurred */
  val time: Long = System.currentTimeMillis

  override def toString(): String = {
    val causeStr =
      s"${cause.getMessage} ${cause.getStackTrace.take(10).mkString("", "\n|\t", "\n")}"
    s"""
       |$message
       |
       |=== Error ===
       |$causeStr
       |
       |=== Offset range ===
       |Start: ${startOffset.map { _.toString }.getOrElse("-")}
       |End:   ${endOffset.map { _.toString }.getOrElse("-")}
       |
       """.stripMargin
  }
}
