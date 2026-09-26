package lab.legacy

object Checksum {
  def of(text: String): Int = text.foldLeft(0)((hash, ch) => (hash * 31 + ch) & 0x7fffffff)
}
