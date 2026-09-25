package lab.svca

import lab.models.Name

object Banner:
  def of(name: Name): String = s"[svc-a] ${name.value}"

  def loud(name: Name): String = of(name).toUpperCase
