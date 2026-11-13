package fs2.serial.backend.windows

import fs2.serial.errors.{
  ListError,
  ReadError,
  SystemError,
  WindowsErrorName,
  WriteError
}
import fs2.serial.testing.{FakeWin32, Start}
import cats.effect.IO
import munit.CatsEffectSuite

final class WindowsBackendSuite extends CatsEffectSuite {

  test("the COM ports are listed as names a person knows, in numeric order") {
    FakeWin32(devices = Right(List("C:", "COM10", "COM2", "NUL", "COM9")))
      .flatMap(fake => new WindowsBackend[IO](fake).list)
      .assertEquals(Right(List("COM2", "COM9", "COM10")))
  }

  test("access denied when listing is AccessDenied") {
    FakeWin32(devices = Left(Start.error(WindowsErrorName.ERROR_ACCESS_DENIED)))
      .flatMap(fake => new WindowsBackend[IO](fake).list)
      .assertEquals(Left(ListError.AccessDenied("QueryDosDevice")))
  }

  test(
    "any other failure when listing is Unreadable, and says which Windows error"
  ) {
    FakeWin32(devices = Left(Start.error(WindowsErrorName.ERROR_GEN_FAILURE)))
      .flatMap(fake => new WindowsBackend[IO](fake).list)
      .assertEquals(
        Left(
          ListError.Unreadable(
            "QueryDosDevice",
            "Windows error 31 (ERROR_GEN_FAILURE)"
          )
        )
      )
  }

  test(
    "the errors that mean the device is gone are Disconnected on read and on write"
  ) {
    List(
      WindowsErrorName.ERROR_DEVICE_NOT_CONNECTED,
      WindowsErrorName.ERROR_GEN_FAILURE,
      WindowsErrorName.ERROR_ACCESS_DENIED,
      WindowsErrorName.ERROR_BAD_COMMAND
    ).foreach { name =>
      assertEquals(
        WindowsIoErrors.read(Start.error(name)),
        ReadError.Disconnected,
        name.toString
      )
      assertEquals(
        WindowsIoErrors.write(Start.error(name)),
        WriteError.Disconnected,
        name.toString
      )
    }
  }

  test("any other error is a plain I/O error that keeps the Windows error") {
    val error = Start.error(WindowsErrorName.ERROR_INVALID_PARAMETER)
    assertEquals(
      WindowsIoErrors.read(error),
      ReadError.Io(SystemError.Windows(error))
    )
    assertEquals(
      WindowsIoErrors.write(error),
      WriteError.Io(SystemError.Windows(error))
    )
  }
}
