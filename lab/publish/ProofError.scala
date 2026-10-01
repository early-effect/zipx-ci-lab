package lab.publish

import zio.json.*

enum ProofError:
  case Cert(detail: String)
  case Bind(detail: String)
  case Protocol(detail: String)
  case Command(args: List[String], exit: Int, detail: String)
  case Failed(name: String, detail: String)

  def message: String = this match
    case Cert(detail)                 => s"certificate: $detail"
    case Bind(detail)                 => s"bind: $detail"
    case Protocol(detail)             => s"protocol: $detail"
    case Command(args, exit, detail)  => s"${args.mkString(" ")} exited $exit\n$detail"
    case Failed(name, detail)         => s"$name: $detail"
end ProofError

final case class Check(name: String, passed: Boolean, detail: String) derives JsonEncoder

final case class Report(passed: Boolean, checks: List[Check]) derives JsonEncoder
