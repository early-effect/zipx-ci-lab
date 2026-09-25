package lab.workerb

import lab.lib.Greeter
import zio.test.*

object TallySpec extends ZIOSpecDefault:
  def spec = suite("worker-b")(
    test("measures each greeting by name") {
      for tally <- Tally.lengths(List("ada", "grace"))
      yield assertTrue(tally == Map("ada" -> 10, "grace" -> 12))
    }
  ).provide(Greeter.layer)
