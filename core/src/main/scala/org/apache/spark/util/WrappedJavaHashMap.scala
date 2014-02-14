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

package org.apache.spark.util

import scala.collection.mutable.Map
import java.util.{Map => JMap}
import java.util.Map.{Entry => JMapEntry}
import scala.collection.{immutable, JavaConversions}
import scala.reflect.ClassTag

/**
 * Convenient wrapper class for exposing Java HashMaps as Scala Maps even if the
 * exposed key-value type is different from the internal type. This allows these
 * implementations of WrappedJavaHashMap to be drop-in replacements for Scala HashMaps.
 *
 * While Java <-> Scala conversion methods exists, its hard to understand the performance
 * implications and thread safety of the Scala wrapper. This class allows you to convert
 * between types and applying the necessary overridden methods to take care of performance.
 *
 * Note that the threading behavior of an implementation of WrappedJavaHashMap is tied to that of
 * the internal Java HashMap used in the implementation. Each implementation must use
 * necessary traits (e.g, scala.collection.mutable.SynchronizedMap), etc. to achieve the
 * desired thread safety.
 *
 * @tparam K  External key type
 * @tparam V  External value type
 * @tparam IK Internal key type
 * @tparam IV Internal value type
 */
private[spark] abstract class WrappedJavaHashMap[K, V, IK, IV] extends Map[K, V] {

  /* Methods that must be defined. */

  /** Internal Java HashMap that is being wrapped. */
  protected[util] val internalJavaMap: JMap[IK, IV]

  /** Method to get a new instance of the internal Java HashMap. */
  protected[util] def newInstance[K1, V1](): WrappedJavaHashMap[K1, V1, _, _]

  /*
    Methods that convert between internal and external types. These implementations
    optimistically assume that the internal types are same as external types. These must
    be overridden if the internal and external types are different. Otherwise there will be
    runtime exceptions.
  */

  @inline protected def externalKeyToInternalKey(k: K): IK = {
    k.asInstanceOf[IK]  // works only if K is same or subclass of K
  }

  @inline protected def externalValueToInternalValue(v: V): IV = {
    v.asInstanceOf[IV]  // works only if V is same or subclass of
  }

  @inline protected def internalKeyToExternalKey(ik: IK): K = {
    ik.asInstanceOf[K]
  }

  @inline protected def internalValueToExternalValue(iv: IV): V = {
    iv.asInstanceOf[V]
  }

  @inline protected def internalPairToExternalPair(ip: JMapEntry[IK, IV]): (K, V) = {
    (internalKeyToExternalKey(ip.getKey), internalValueToExternalValue(ip.getValue) )
  }

  /* Implicit methods to convert the types. */

  @inline implicit private def convExtKeyToIntKey(k: K) = externalKeyToInternalKey(k)

  @inline implicit private def convExtValueToIntValue(v: V) = externalValueToInternalValue(v)

  @inline implicit private def convIntKeyToExtKey(ia: IK) = internalKeyToExternalKey(ia)

  @inline implicit private def convIntValueToExtValue(ib: IV) = internalValueToExternalValue(ib)

  @inline implicit private def convIntPairToExtPair(ip: JMapEntry[IK, IV]) = {
    internalPairToExternalPair(ip)
  }

  /* Methods that must be implemented for a scala.collection.mutable.Map */

  def get(key: K): Option[V] = {
    Option(internalJavaMap.get(key))
  }

  def iterator: Iterator[(K, V)] = {
    val jIterator = internalJavaMap.entrySet().iterator()
    JavaConversions.asScalaIterator(jIterator).map(kv => convIntPairToExtPair(kv))
  }

  /* Other methods that are implemented to ensure performance. */

  def +=(kv: (K, V)): this.type = {
    internalJavaMap.put(kv._1, kv._2)
    this
  }

  def -=(key: K): this.type = {
    internalJavaMap.remove(key)
    this
  }

  override def + [V1 >: V](kv: (K, V1)): Map[K, V1] = {
    val newMap = newInstance[K, V1]()
    newMap.internalJavaMap.asInstanceOf[JMap[IK, IV]].putAll(this.internalJavaMap)
    newMap += kv
    newMap
  }

  override def - (key: K): Map[K, V] = {
    val newMap = newInstance[K, V]()
    newMap.internalJavaMap.asInstanceOf[JMap[IK, IV]].putAll(this.internalJavaMap)
    newMap -= key
  }

  override def foreach[U](f: ((K, V)) => U) {
    val jIterator = internalJavaMap.entrySet().iterator()
    while(jIterator.hasNext) {
      f(jIterator.next())
    }
  }

  override def empty: Map[K, V] = newInstance[K, V]()

  override def size: Int = internalJavaMap.size

  override def filter(p: ((K, V)) => Boolean): Map[K, V] = {
    newInstance[K, V]() ++= iterator.filter(p)
  }

  def toMap: immutable.Map[K, V] = iterator.toMap
}
