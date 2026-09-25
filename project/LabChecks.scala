import zipx.core.*
import zipx.shell.*
import zipx.workflow.Step

/** Assertions a lab scenario runs inside CI, where the thing it checks exists. */
object LabChecks:

  /** L2: fails when any class under `target/out/jvm`, compiled by this job or restored from the cache, is
    * coverage-instrumented.
    *
    * Reads class files rather than staged jars: the Scala 3 library jar ships the coverage runtime itself.
    * `coverage/Invoker` matches both runtimes, Scala 2's `scoverage/Invoker` and Scala 3's built-in
    * `scala/runtime/coverage/Invoker`. The meta-build is excluded because this file holds that string.
    */
  val uninstrumented: Steps = Steps("uninstrumented") { _ =>
    val instrumented = ShTest.Cmd(
      Exec(
        "grep",
        Word.lit("-rlaF"),
        Word.lit("coverage/Invoker"),
        Word.squote("--include=*.class"),
        Word.squote("--exclude-dir=*-build"),
        Word.lit("target/out/jvm"),
      )
    )
    List(
      Step
        .run(
          Script(
            If(
              ShTest.Not(ShTest.DirExists(Word.lit("target/out/jvm"))),
              Block(Exec("echo", Word.quoted("no compiled classes to check")), Exit(ExitCode.Failure)),
            ),
            If(
              instrumented,
              Block(Exec("echo", Word.quoted("coverage-instrumented classes above")), Exit(ExitCode.Failure)),
            ),
            Exec("echo", Word.quoted("no coverage instrumentation in compiled classes")),
          )
        )
        .named("Check compiled classes are uninstrumented")
        .build
    )
  }
end LabChecks
