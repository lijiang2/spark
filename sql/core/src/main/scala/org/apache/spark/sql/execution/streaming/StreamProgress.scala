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

import scala.collection.mutable

class StreamProgress extends Serializable {
  private val currentOffsets = new mutable.HashMap[Source, Offset]
    with mutable.SynchronizedMap[Source, Offset]

  def isEmpty: Boolean = currentOffsets.filterNot(_._2.isEmpty).isEmpty

  def update(source: Source, newOffset: Offset): Unit = {
    currentOffsets.get(source).foreach(old => assert(newOffset > old))
    currentOffsets.put(source, newOffset)
  }

  def update(newOffset: (Source, Offset)): Unit =
    update(newOffset._1, newOffset._2)

  def apply(source: Source): Offset = currentOffsets(source)
  def get(source: Source): Option[Offset] = currentOffsets.get(source)
  def contains(source: Source): Boolean = currentOffsets.contains(source)

  def ++(updates: Map[Source, Offset]): StreamProgress = {
    val updated = new StreamProgress
    currentOffsets.foreach(updated.update)
    updates.foreach(updated.update)
    updated
  }

  def copy(): StreamProgress = {
    val copied = new StreamProgress
    currentOffsets.foreach(copied.update)
    copied
  }

  override def toString: String =
    currentOffsets.map { case (k, v) => s"$k: $v"}.mkString("{", ",", "}")

  override def equals(other: Any): Boolean = other match {
    case s: StreamProgress =>
      s.currentOffsets.keys.toSet == currentOffsets.keys.toSet &&
      s.currentOffsets.forall(w => currentOffsets(w._1) == w._2)
  }

  override def hashCode: Int = {
    currentOffsets.toSeq.sortBy(_._1.toString).hashCode()
  }
}