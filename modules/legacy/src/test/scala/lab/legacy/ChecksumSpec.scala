package lab.legacy

import zio.test._

object ChecksumSpec extends ZIOSpecDefault {
  def spec = suite("Checksum")(
    test("matches String.hashCode for short ASCII input") {
      assertTrue(Checksum.of("ada") == "ada".hashCode)
    },
    test("never goes negative") {
      check(Gen.string)(s => assertTrue(Checksum.of(s) >= 0))
    }
  )
}
