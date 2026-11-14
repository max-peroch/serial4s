ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.max-peroch"
ThisBuild / homepage   := Some(uri("https://github.com/max-peroch/serial4s"))
ThisBuild / developers := List(
  Developer(
    "max-peroch",
    "max-peroch",
    "",
    uri("https://github.com/max-peroch")
  )
)
ThisBuild / scalafmtOnCompile := true
ThisBuild / licenses          := Seq(License.Apache2)
ThisBuild / scalacOptions     := Seq(
  "-Wunused:all",
  "-Werror",
  // FFM (java.lang.foreign) is final in JDK 22: this keeps the build off newer-only APIs.
  "-release",
  "22"
)

// Checked, in binary compatibility, against the version released before the one being built
// (`mimaReportBinaryIssues`). There is nothing to compare with until the first release.
lazy val againstLastRelease = Seq(
  mimaPreviousArtifacts := previousStableVersion.value
    .map(version => organization.value %% moduleName.value % version)
    .toSet
)

lazy val root = rootProject
  .settings(
    againstLastRelease,
    name := "serial4s",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.7.1",
      "co.fs2"        %% "fs2-io"              % "3.14.0",
      "org.typelevel" %% "cats-effect-testkit" % "3.7.1" % Test,
      "org.scalameta" %% "munit"               % "1.3.6" % Test,
      "org.typelevel" %% "munit-cats-effect"   % "2.2.1" % Test
    ),
    // FFM needs native access. `deny` makes any missing grant a hard failure, so the tests
    // already run under the strict mode a later JDK is expected to make the default.
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--illegal-native-access=deny"
    )
  )
