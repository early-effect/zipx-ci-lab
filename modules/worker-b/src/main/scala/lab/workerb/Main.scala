package lab.workerb

import lab.lib.Greeter
import lab.models.Name
import zio.*

object Tally:
  def lengths(raw: List[String]): URIO[Greeter, Map[String, Int]] =
    ZIO
      .foreach(raw.flatMap(Name.make(_).toOption))(name => Greeter.greet(name).map(name.value -> _.length))
      .map(_.toMap)

object Main extends ZIOAppDefault:
  def run =
    Tally.lengths(List("ada", "joan")).flatMap(tally => Console.printLine(tally.toString)).provide(Greeter.layer)
