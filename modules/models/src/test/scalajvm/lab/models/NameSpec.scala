package lab.models

import zio.test.*

object NameSpec extends ZIOSpecDefault:
  def spec = suite("Name")(
    test("trims surrounding whitespace") {
      assertTrue(Name.make("  ada ").map(_.value) == Right("ada"))
    },
    test("rejects a blank name") {
      assertTrue(Name.make("   ") == Left(NameError.Blank))
    },
  )
