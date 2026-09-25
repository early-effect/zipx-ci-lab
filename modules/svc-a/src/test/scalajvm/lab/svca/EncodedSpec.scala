package lab.svca

import lab.lib.Greeter
import lab.models.Name
import zio.*
import zio.test.*

object EncodedSpec extends ZIOSpecDefault:
  def spec = suite("svc-a")(
    test("hex-encodes the greeting as UTF-8") {
      for
        name <- ZIO.fromEither(Name.make("ada"))
        hex  <- Encoded.greeting(name)
      yield assertTrue(hex == "68656c6c6f2c20616461")
    },
    test("banner names the service") {
      assertTrue(Name.make("ada").map(Banner.of) == Right("[svc-a] ada"))
    },
  ).provide(Greeter.layer)
end EncodedSpec
