package lab.svca

import lab.models.Name

object Banner:
  def of(name: Name): String = "[svc-a] " + name.value
