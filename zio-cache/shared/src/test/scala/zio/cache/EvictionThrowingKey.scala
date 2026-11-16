package zio.cache

/**
 * A cache key whose hash code throws when the cache computes it to evict an
 * entry, while `throwsDuringEviction` is set. It reads the stack trace to know
 * whether the cache is evicting, so it only works on the JVM.
 */
final class EvictionThrowingKey(val id: Int) {
  @volatile var throwsDuringEviction: Boolean = false

  override def hashCode(): Int = {
    val isEvicting =
      throwsDuringEviction && Thread.currentThread.getStackTrace.exists(_.getMethodName.contains("trackAccess"))
    // `NonFatal` does not match a `LinkageError`, but ZIO turns it into a defect
    if (isEvicting) throw new LinkageError("hashCode")
    id
  }

  override def equals(that: Any): Boolean =
    that match {
      case key: EvictionThrowingKey => key.id == id
      case _                        => false
    }
}
