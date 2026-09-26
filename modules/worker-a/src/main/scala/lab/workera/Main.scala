package lab.workera

import lab.lib.Greeter
import lab.models.Name
import zio.*

object Batch:
  def greetAll(raw: List[String]): URIO[Greeter, List[String]] =
    ZIO.foreach(raw.flatMap(Name.make(_).toOption))(Greeter.greet)

object Main extends ZIOAppDefault:
  def run =
    Batch.greetAll(List("ada", "grace")).flatMap(ZIO.foreachDiscard(_)(Console.printLine(_))).provide(Greeter.layer)
