import zipx.core.*
import zipx.shell.*
import zipx.workflow.Step

/** Assertions a lab scenario runs inside CI, where the thing it checks exists. */
object LabChecks:

  /** L2: fails when a staged image jar holds coverage-instrumented classes, or when nothing was staged to check.
    *
    * `coverage/Invoker` matches both runtimes: Scala 2's `scoverage/Invoker` and Scala 3's built-in
    * `scala/runtime/coverage/Invoker`. `grep -c` rather than `-q`, which would close the pipe early and turn a match
    * into a `pipefail` failure.
    */
  val uninstrumented: Steps = Steps("uninstrumented") { _ =>
    val findJars =
      Exec(
        "find",
        Word.lit("."),
        Word.lit("-path"),
        Word.squote("*/docker/stage/*"),
        Word.lit("-name"),
        Word.squote("*.jar"),
      )
    val instrumented = ShTest.Cmd(
      Silence(
        Pipe(
          Exec("unzip", Word.lit("-p"), Word.vq("jar")),
          Exec("grep", Word.lit("-ac"), Word.lit("coverage/Invoker")),
        )
      )
    )
    List(
      Step
        .run(
          Script(
            Assign("jars", Word.subst(findJars)),
            If(
              ShTest.Empty(Word.vq("jars")),
              Block(Exec("echo", Word.quoted("no staged jars to check")), Exit(ExitCode.Failure)),
            ),
            ForIn(
              VarName("jar"),
              List(Word.v("jars")),
              Block(
                If(
                  instrumented,
                  Block(
                    Exec("echo", Word.dquote(Word.lit("instrumented classes in "), Word.v("jar"))),
                    Exit(ExitCode.Failure),
                  ),
                )
              ),
            ),
            Exec("echo", Word.quoted("no coverage instrumentation in staged jars")),
          )
        )
        .named("Check staged jars are uninstrumented")
        .build
    )
  }
end LabChecks
