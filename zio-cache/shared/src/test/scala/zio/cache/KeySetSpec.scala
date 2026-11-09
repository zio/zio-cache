package zio.cache

import zio.test._

object KeySetSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] = suite("KeySetSpec")(
    test("add moves a key that is already in the set to the end") {
      val (set, keys) = make(3)
      set.add(keys(0))
      assertTrue(drain(set) == List(1, 2, 0))
    },
    test("size counts each key once") {
      val (set, keys) = make(3)
      set.add(keys(1))
      set.add(keys(2))
      assertTrue(set.size == 3)
    },
    test("retain removes the first, a middle and the last key") {
      val (set, _) = make(5)
      set.retain(key => key.value == 1 || key.value == 3)
      assertTrue(set.size == 2, drain(set) == List(1, 3))
    },
    test("retain can remove every key") {
      val (set, _) = make(3)
      set.retain(_ => false)
      assertTrue(set.size == 0, set.remove() eq null)
    },
    test("a key removed by retain can be added again") {
      val (set, keys) = make(3)
      set.retain(_.value != 1)
      set.add(keys(1))
      assertTrue(set.size == 3, drain(set) == List(0, 2, 1))
    }
  )

  private def make(n: Int): (KeySet[Int], Vector[MapKey[Int]]) = {
    val set  = new KeySet[Int]
    val keys = Vector.tabulate(n)(new MapKey(_))
    keys.foreach(set.add)
    (set, keys)
  }

  private def drain(set: KeySet[Int]): List[Int] = {
    val key = set.remove()
    if (key eq null) Nil else key.value :: drain(set)
  }
}
