package lab.svca

import lab.models.Name

object Banner:
  def of(name: Name): String = s"[svc-a] ${name.value}"

  def framed(name: Name): String = s"| ${of(name)} |"
