package lab.svcb

import lab.lib.Greeter
import lab.models.Name
import zio.*

object Styled:
  def greeting(name: Name): URIO[Greeter, fansi.Str] = Greeter.greet(name).map(fansi.Color.Green(_))

object Main extends ZIOAppDefault:
  def run =
    ZIO
      .fromEither(Name.make("svc-b"))
      .flatMap(Styled.greeting)
      .flatMap(styled => Console.printLine(styled.render))
      .provide(Greeter.layer)
