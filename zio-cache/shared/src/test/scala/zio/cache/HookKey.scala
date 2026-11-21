package zio.cache

/**
 * A cache key that runs a hook the n-th time its hash code is computed, which
 * lets a test change the cache in the middle of an operation on that key.
 */
final class HookKey(val id: Int) {
  @volatile private var countdown: Int   = 0
  @volatile private var hook: () => Unit = null

  /**
   * Runs `f` once, when the hash code is computed for the `n`-th time from now.
   */
  def onHash(n: Int)(f: => Unit): Unit = {
    countdown = n
    hook = () => f
  }

  /**
   * Makes every later computation of the hash code throw.
   */
  def throwOnEveryHash(): Unit =
    onHash(1) {
      throwOnEveryHash()
      throw new RuntimeException("hashCode")
    }

  override def hashCode(): Int = {
    val current = hook
    if (current ne null) {
      countdown -= 1
      val isHookCall = countdown == 0
      if (isHookCall) {
        hook = null
        current()
      }
    }
    id
  }

  override def equals(that: Any): Boolean =
    that match {
      case key: HookKey => key.id == id
      case _            => false
    }
}
