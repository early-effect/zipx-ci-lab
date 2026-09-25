package lab.models

opaque type Name = String

object Name:
  def make(raw: String): Either[NameError, Name] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(NameError.Blank) else Right(trimmed)

  extension (name: Name) def value: String = name

enum NameError:
  case Blank
