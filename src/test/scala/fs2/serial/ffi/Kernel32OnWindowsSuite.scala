package fs2.serial.ffi

import cats.effect.IO
import fs2.serial.Serial
import fs2.serial.errors.OpenError
import fs2.serial.settings.SerialSettings
import fs2.serial.win32.Win32
import munit.CatsEffectSuite

/** The real `kernel32`, on a Windows machine, with no port needed. */
final class Kernel32OnWindowsSuite extends CatsEffectSuite {

  private val onWindows: Boolean =
    Option(System.getProperty("os.name")).exists(_.startsWith("Windows"))

  private val win32: IO[Win32[IO]] =
    WindowsLib
      .load[IO]
      .flatMap(
        _.fold(
          unavailable => IO.raiseError(new AssertionError(unavailable.reason)),
          IO.pure
        )
      )

  test("kernel32 loads and binds every call") {
    assume(onWindows, "this machine is not Windows")
    IO.delay(Kernel32.load())
      .map(loaded => assert(loaded.isRight, loaded.toString))
  }

  test("the DOS devices are listed, as names") {
    assume(onWindows, "this machine is not Windows")
    win32.flatMap(_.dosDevices).map {
      case Right(names) =>
        assert(names.nonEmpty, "expected at least one DOS device")
      case Left(error) => fail(s"QueryDosDevice failed: $error")
    }
  }

  test(
    "a port that does not exist is NoSuchPort, through the real CreateFileW"
  ) {
    assume(onWindows, "this machine is not Windows")
    Serial
      .make[IO]
      .flatMap(
        _.fold(
          error => IO.raiseError(new AssertionError(s"make: $error")),
          _.open("COM255", SerialSettings()).use(IO.pure)
        )
      )
      .map(_.map(_ => ()))
      .assertEquals(Left(OpenError.NoSuchPort("COM255")))
  }

  test("an event can be made, signalled and reset") {
    assume(onWindows, "this machine is not Windows")
    win32.flatMap(w =>
      w.event.use {
        case Right(event) => w.signal(event) *> w.reset(event)
        case Left(error)  => IO.raiseError(new AssertionError(s"event: $error"))
      }
    )
  }

  test("a transfer buffer can be made, filled and read back") {
    assume(onWindows, "this machine is not Windows")
    win32.flatMap(w =>
      w.operation(16).use {
        case Right(operation) =>
          for {
            _     <- operation.fill(Array[Byte](1, 2, 3), 0, 3)
            bytes <- operation.bytes(3)
          } yield assertEquals(bytes.toList, List[Byte](1, 2, 3))
        case Left(error) =>
          IO.raiseError(new AssertionError(s"operation: $error"))
      }
    )
  }
}
