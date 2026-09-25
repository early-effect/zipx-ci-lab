package lab.svca

import lab.lib.Greeter
import lab.models.Name
import scodec.bits.ByteVector
import zio.*

import java.nio.charset.StandardCharsets

object Encoded:
  def greeting(name: Name): URIO[Greeter, String] =
    Greeter.greet(name).map(text => ByteVector.view(text.getBytes(StandardCharsets.UTF_8)).toHex)

object Main extends ZIOAppDefault:
  def run =
    ZIO
      .fromEither(Name.make("svc-a"))
      .flatMap(name => Encoded.greeting(name).flatMap(hex => Console.printLine(s"${Banner.of(name)} $hex")))
      .provide(Greeter.layer)
