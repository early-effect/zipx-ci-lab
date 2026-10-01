resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"

addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.17.0-SNAPSHOT")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
