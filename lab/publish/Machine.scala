package lab.publish

import zio.*

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

object Machine:
  final case class Ran(command: List[String], exit: Int, tail: String)

  def run(
      dir: Path,
      command: List[String],
      extra: Map[String, String] = Map.empty,
      unset: List[String] = Nil,
      stdin: Option[String] = None,
  ): IO[ProofError, Ran] =
    ZIO.scoped {
      for
        proc <- ZIO.acquireRelease(
          ZIO.attemptBlocking(start(dir, command, extra, unset, stdin)).mapError(boom(command, _))
        )(proc => ZIO.attemptBlocking(proc.destroy()).ignore)
        ran <- ZIO.attemptBlockingInterrupt(read(command, proc)).mapError(boom(command, _))
      yield ran
    }

  def sbt(dir: Path, args: List[String], env: Map[String, String], unset: List[String] = Nil): IO[ProofError, Ran] =
    val (props, commands) = args.span(_.startsWith("-"))
    val line              = if commands.isEmpty then Nil else List(commands.mkString(" "))
    run(dir, "sbt" :: "--server" :: "--batch" :: (props ::: line), env, unset)

  private def marked(line: String): Boolean =
    val lower = line.toLowerCase
    lower.contains("viamodels=") || lower.contains("already released") || lower.contains("is not a tag") ||
      lower.contains("is not in the catalog") || lower.contains("no credentials") ||
      lower.contains("cannot tell whether") || lower.contains("http 401") || lower.contains("x509") ||
      lower.contains("unknown authority") || lower.contains("zipxsnapshotstatus") ||
      lower.contains("zipxsnapshotpublish local") || lower.contains("not on the release repository") ||
      lower.contains("all refuses") || lower.contains("changing=")

  def tailOf(text: String, n: Int = 40): String =
    val lines = text.split('\n').toList
    val kept  = if lines.length <= n then lines else lines.takeRight(n)
    kept.mkString("\n")

  private def start(
      dir: Path,
      command: List[String],
      extra: Map[String, String],
      unset: List[String],
      stdin: Option[String],
  ): Process =
    val pb = new ProcessBuilder(command*)
    pb.directory(dir.toFile)
    pb.redirectErrorStream(true)
    val env = pb.environment()
    extra.foreach((k, v) => env.put(k, v))
    unset.foreach(env.remove)
    val proc = pb.start()
    stdin.foreach { text =>
      val out = proc.getOutputStream
      out.write(text.getBytes(StandardCharsets.UTF_8))
      if !text.endsWith("\n") then out.write('\n')
      out.close()
    }
    proc

  private def read(command: List[String], proc: Process): Ran =
    val reader = new BufferedReader(new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8))
    val kept   = new java.util.ArrayDeque[String](80)
    val marked = List.newBuilder[String]
    Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
      java.lang.System.out.println(line)
      if kept.size == 80 then kept.removeFirst()
      kept.addLast(line)
      if Machine.marked(line) then marked.addOne(line)
    }
    val exit = proc.waitFor()
    val tail = (marked.result() ++ kept.asScala).distinct.mkString("\n")
    Ran(command, exit, tail)

  private def boom(command: List[String], err: Throwable): ProofError =
    ProofError.Command(command, -1, String.valueOf(err.getMessage))
end Machine
