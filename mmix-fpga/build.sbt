scalaVersion := "2.13.14"

Compile / scalaSource := baseDirectory.value / "src"
Test    / scalaSource := baseDirectory.value / "test"

libraryDependencies += "org.chipsalliance" %% "chisel" % "6.6.0"
addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.6.0" cross CrossVersion.full)
