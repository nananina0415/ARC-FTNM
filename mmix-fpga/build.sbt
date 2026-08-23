scalaVersion := "2.13.14"

Compile / scalaSource := baseDirectory.value / "src"
Test    / scalaSource := baseDirectory.value / "test"

Test / fork          := true
Test / baseDirectory := baseDirectory.value / "test"

libraryDependencies += "org.chipsalliance" %% "chisel"      % "6.6.0"
libraryDependencies += "edu.berkeley.cs"  %% "chiseltest"  % "6.0.0" % Test
addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.6.0" cross CrossVersion.full)
