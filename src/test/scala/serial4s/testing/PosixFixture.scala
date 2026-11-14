package serial4s.testing

import serial4s.ffi.Libc
import serial4s.platform.Platform
import serial4s.posix.Posix
import cats.effect.{IO, Resource}
import munit.catseffect.IOFixture
import munit.{AnyFixture, CatsEffectSuite}

/** The real [[Posix]], loaded once per suite. */
private[serial4s] trait PosixFixture extends CatsEffectSuite {

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
