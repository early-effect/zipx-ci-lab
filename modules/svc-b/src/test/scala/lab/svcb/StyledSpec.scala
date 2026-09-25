package lab.svcb

import lab.lib.Greeter
import lab.models.Name
import zio.*
import zio.test.*

object StyledSpec extends ZIOSpecDefault:
  def spec = suite("svc-b")(
    test("colors the greeting without changing its text") {
      for
        name   <- ZIO.fromEither(Name.make("ada"))
        styled <- Styled.greeting(name)
      yield assertTrue(styled.plainText == "hello, ada", styled.render != styled.plainText)
    }
  ).provide(Greeter.layer)
