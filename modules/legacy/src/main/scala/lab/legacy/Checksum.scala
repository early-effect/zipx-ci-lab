package lab.legacy

object Checksum {
  def of(text: String): Int = text.foldLeft(0)((acc, c) => (acc * 31 + c) & 0x7fffffff)
}
