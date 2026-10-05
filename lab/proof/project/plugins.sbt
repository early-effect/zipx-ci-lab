resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
forceUpdatePeriod := Some(scala.concurrent.duration.Duration.Zero)

addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.18.0-20378432e866-SNAPSHOT")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
