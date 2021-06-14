package java.util.concurrent.atomic

/**
 * An implementation of `LongAdder` for Scala Native.
 */
class LongAdder {

  /**
   * The current value.
   */
  private var value: Long = 0L

  /**
   * Increments the current value.
   */
  def increment(): Unit =
    value += 1

  /**
   * Returns the current value.
   */
  def longValue: Long =
    value
}
