package lab.publish

import zio.*

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

/** A throwaway git clone of `lab/proof`. The lab checkout's HEAD is not the sha under test. */
object ProofClone:
  def prepare(source: Path, pluginRepo: Path): IO[ProofError, Path] =
    ZIO.attemptBlocking {
      val dest = Files.createTempDirectory("zipx-proof-clone")
      copy(source, dest)
      val plugins = dest.resolve("project/plugins.sbt")
      val text    = Files.readString(plugins)
      if !text.contains("\"0.17.0-SNAPSHOT\"") then
        throw new RuntimeException(s"$plugins does not pin sbt-zipx 0.17.0-SNAPSHOT")
      val rewritten =
        s"""resolvers += "zipx-under-test" at "${pluginRepo.toAbsolutePath.toUri}"
           |${text.replace("0.17.0-SNAPSHOT", "0.17.0-ci")}
           |""".stripMargin
      Files.writeString(plugins, rewritten)
      Files.writeString(
        dest.resolve(".gitignore"),
        "target/\n.bsp/\n.bloop/\nproject/target/\nproject/project/\n",
      )
      dest
    }.mapError(err => ProofError.Failed("proof-clone", String.valueOf(err.getMessage))).flatMap { dest =>
      git(dest, "init", "-b", "main") *>
        git(dest, "config", "user.email", "zipx@example.com") *>
        git(dest, "config", "user.name", "zipx") *>
        git(dest, "add", "-A") *>
        git(dest, "-c", "user.email=zipx@example.com", "-c", "user.name=zipx", "commit", "-m", "init")
          .as(dest)
    }

  def head(dir: Path): IO[ProofError, String] =
    Machine.run(dir, List("git", "rev-parse", "HEAD")).flatMap { ran =>
      val sha = ran.tail.trim.linesIterator.filter(_.matches("[0-9a-f]{40}")).toList
      sha match
        case full :: Nil if ran.exit == 0 => ZIO.succeed(full)
        case _                            => ZIO.fail(ProofError.Failed("git-head", ran.tail))
    }

  def commitAll(dir: Path, message: String): IO[ProofError, Unit] =
    git(dir, "add", "-A") *>
      git(dir, "-c", "user.email=zipx@example.com", "-c", "user.name=zipx", "commit", "-m", message).unit

  def resetHard(dir: Path): IO[ProofError, Unit] =
    git(dir, "reset", "--hard", "HEAD").unit

  private def git(dir: Path, args: String*): IO[ProofError, Machine.Ran] =
    Machine.run(dir, "git" :: args.toList).flatMap { ran =>
      if ran.exit == 0 then ZIO.succeed(ran)
      else ZIO.fail(ProofError.Command(ran.command, ran.exit, ran.tail))
    }

  private def copy(from: Path, to: Path): Unit =
    val stream = Files.walk(from)
    try
      stream.iterator.asScala.foreach { src =>
        val rel = from.relativize(src).toString
        if !skip(rel) then
          val dest = to.resolve(rel)
          if Files.isDirectory(src) then Files.createDirectories(dest)
          else
            Files.createDirectories(dest.getParent)
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      }
    finally stream.close()

  private def skip(rel: String): Boolean =
    rel.split('/').exists(part => part == "target" || part == ".bsp" || part == ".bloop")
end ProofClone
