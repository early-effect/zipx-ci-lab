package lab.svca

import lab.models.Name

object Banner:
  def of(name: Name): String = s"[svc-a] hello, ${name.value}"
