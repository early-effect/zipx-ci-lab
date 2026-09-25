package lab.workera

import lab.lib.Greeter
import zio.test.*

object BatchSpec extends ZIOSpecDefault:
  def spec = suite("worker-a")(
    test("greets every valid name and drops blanks") {
      for greetings <- Batch.greetAll(List("ada", " ", "grace"))
      yield assertTrue(greetings == List("hello, ada", "hello, grace"))
    }
  ).provide(Greeter.layer)
