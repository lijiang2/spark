package org.apache.spark.streaming

import org.apache.spark.SparkFunSuite
import org.apache.spark.streaming.dstream.HashMapBasedSessionMap
import org.apache.spark.streaming.dstream.Session

class SessionMapSuite extends SparkFunSuite {
  test("put, get, remove, iterator") {
    val map = new HashMapBasedSessionMap[Int, Int]()

    map.put(1, 100)
    assert(map.get(1) === Some(100))
    assert(map.get(2) === None)
    map.put(2, 200)
    assert(map.iterator(updatedSessionsOnly = true).toSet ===
      Set(Session(1, 100, true), Session(2, 200, true)))
    assert(map.iterator(updatedSessionsOnly = false).toSet ===
      Set(Session(1, 100, true), Session(2, 200, true)))

    map.remove(1)
    assert(map.get(1) === None)

    assert(map.iterator(updatedSessionsOnly = true).toSet ===
      Set(Session(1, 100, false), Session(2, 200, true)))
    assert(map.iterator(updatedSessionsOnly = false).toSet ===
      Set(Session(1, 100, false), Session(2, 200, true)))
  }

  test("put, get, remove, iterator after copy") {
    val parentMap = new HashMapBasedSessionMap[Int, Int]()
    parentMap.put(1, 100)
    parentMap.put(2, 200)
    parentMap.remove(1)

    val map = parentMap.copy()
    assert(map.iterator(updatedSessionsOnly = true).toSet === Set())
    assert(map.iterator(updatedSessionsOnly = false).toSet ===
      Set(Session(1, 100, false), Session(2, 200, true)))

    map.put(3, 300)
    map.put(4, 400)
    map.remove(4)

    assert(map.iterator(updatedSessionsOnly = true).toSet ===
      Set(Session(3, 300, true), Session(4, 400, false)))
    assert(map.iterator(updatedSessionsOnly = false).toSet ===
      Set(Session(1, 100, false), Session(2, 200, true),
        Session(3, 300, true), Session(4, 400, false)))

    assert(parentMap.iterator(updatedSessionsOnly = true).toSet ===
      Set(Session(1, 100, false), Session(2, 200, true)))
    assert(parentMap.iterator(updatedSessionsOnly = false).toSet ===
      Set(Session(1, 100, false), Session(2, 200, true)))

    map.put(1, 1000)
    map.put(2, 2000)
    assert(map.iterator(updatedSessionsOnly = true).toSet ===
      Set(Session(3, 300, true), Session(4, 400, false),
        Session(1, 1000, true), Session(2, 2000, true)))
    assert(map.iterator(updatedSessionsOnly = false).toSet ===
      Set(Session(1, 1000, true), Session(2, 2000, true),
        Session(3, 300, true), Session(4, 400, false)))
  }

  test("copying with consolidation") {
    val map1 = new HashMapBasedSessionMap[Int, Int]()
    map1.put(1, 100)
    map1.put(2, 200)

    val map2 = map1.copy()
    map2.put(3, 300)
    map2.put(4, 400)

    val map3 = map2.copy()
    map3.put(3, 600)
    map3.put(4, 700)

    assert(map3.iterator(false).toSet ===
      map3.asInstanceOf[HashMapBasedSessionMap[Int, Int]].doCopy(true).iterator(false).toSet)

  }
}
