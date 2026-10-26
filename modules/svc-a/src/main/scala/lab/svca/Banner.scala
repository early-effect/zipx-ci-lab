package lab.svca

import lab.models.Name

object Banner:
  def of(name: Name): String = s"[svc-a] ${name.value}"

  def quiet(name: Name): String = of(name).toLowerCase
