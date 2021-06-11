package java.util.concurrent.atomic

class LongAdder {
  private var value: Long = 0L
  def increment(): Unit   =
    value += 1
  def longValue: Long     =
    value
}
