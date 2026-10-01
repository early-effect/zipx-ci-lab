package lab.lib

import lab.models.Name
import zio.*

final case class Greeter(salutation: String):
  def greet(name: Name): UIO[String] = ZIO.succeed(List(salutation, name.value).mkString(", "))

object Greeter:
  val layer: ULayer[Greeter] = ZLayer.succeed(Greeter("hello"))

  def greet(name: Name): URIO[Greeter, String] = ZIO.serviceWithZIO[Greeter](_.greet(name))
