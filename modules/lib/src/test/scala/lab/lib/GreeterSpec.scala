package lab.lib

import lab.models.Name
import zio.*
import zio.test.*

object GreeterSpec extends ZIOSpecDefault:
  def spec = suite("Greeter")(
    test("greets with the configured salutation") {
      for
        name     <- ZIO.fromEither(Name.make("ada"))
        greeting <- Greeter.greet(name)
      yield assertTrue(greeting == "hello, ada")
    }
  ).provide(Greeter.layer)
