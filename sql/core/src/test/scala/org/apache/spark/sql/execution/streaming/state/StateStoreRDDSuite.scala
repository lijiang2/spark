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

package org.apache.spark.sql.execution.streaming.state

import scala.util.Random

import org.scalatest.BeforeAndAfter

import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils
import org.apache.spark.{SharedSparkContext, SparkFunSuite}


class StateStoreRDDSuite extends SparkFunSuite with BeforeAndAfter with SharedSparkContext {
  private var tempDir = Utils.createTempDir().toString

  import StateStoreSuite._

  after {
    StateStore.clearAll()
  }

  test("StateStoreRDD") {

    val path = Utils.createDirectory(tempDir, Random.nextString(10)).toString
    val increment = (store: StateStore, iter: Iterator[String]) => {
      iter.foreach { s =>
        store.update(wrapKey(s), oldRow => {
          val oldValue = oldRow.map(unwrapValue).getOrElse(0)
          wrapValue(oldValue + 1)
        })
      }
      store.commitUpdates()
      store.getAll().map(unwrapKeyValue)
    }
    val opId = 0
    val rdd1 = makeRDD(Seq("a", "b", "a"))
      .withStateStores(increment, opId, newStoreVersion = 0, path, null)
    assert(rdd1.collect().toSet === Set("a" -> 2, "b" -> 1))

    val rdd2 = makeRDD(Seq("a", "c"))
      .withStateStores(increment, opId, newStoreVersion = 1, path, null)
    assert(rdd2.collect().toSet === Set("a" -> 3, "b" -> 1, "c" -> 1))
  }

  private def makeRDD(seq: Seq[String]): RDD[String] = {
    sc.makeRDD(seq, 2).groupBy(x => x).flatMap(_._2)
  }
}
