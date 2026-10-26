package lab.svca

import lab.models.Name

object Banner:
  /** L1 run 2. */
  def of(name: Name): String = s"[svc-a] ${name.value}"
