package fs2.serial.testing

import fs2.serial.ffi.Libc
import fs2.serial.platform.Platform
import fs2.serial.posix.Posix
import cats.effect.{IO, Resource}
import munit.catseffect.IOFixture
import munit.{AnyFixture, CatsEffectSuite}

/** The real [[Posix]], loaded once per suite. */
private[serial] trait PosixFixture extends CatsEffectSuite {

  /** These suites use pseudo-terminals and the POSIX calls: not on Windows. */
  override def munitIgnore: Boolean =
    Option(System.getProperty("os.name")).exists(_.startsWith("Windows"))

  protected val posixFixture: IOFixture[Posix[IO]] =
    ResourceSuiteLocalFixture(
      "posix",
      Resource.eval(
        IO(Platform.detect()).flatMap {
          case Left(unavailable) =>
            IO.raiseError(new AssertionError(unavailable.reason))
          case Right(platform) =>
            Libc
              .load[IO](platform)
              .flatMap(
                _.fold(
                  unavailable =>
                    IO.raiseError(new AssertionError(unavailable.reason)),
                  IO.pure
                )
              )
        }
      )
    )

  override def munitFixtures: Seq[AnyFixture[?]] = List(posixFixture)

  protected def posix: Posix[IO] = posixFixture()

  protected def platform: Platform =
    Platform.detect().fold(unavailable => fail(unavailable.reason), identity)
}
