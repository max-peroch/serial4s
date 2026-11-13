package fs2.serial.backend.windows

import fs2.serial.errors.{OpenError, SystemError, WindowsErrorName}
import fs2.serial.settings.SerialSettings
import fs2.serial.testing.{FakeWin32, Start}
import fs2.serial.win32.Handle
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

final class WindowsOpenerSuite extends CatsEffectSuite {

  private def error(name: WindowsErrorName) = Start.error(name)

  private def opened(fake: FakeWin32, path: String = "COM3") =
    WindowsOpener.open(fake, path, SerialSettings()).use(IO.pure)

  test("a port name is opened as a device path") {
    for {
      fake   <- FakeWin32()
      result <- opened(fake, "COM3")
      log    <- fake.log.get
    } yield {
      assertEquals(result, Right(Handle(1L)))
      assert(log.contains("""open \\.\COM3"""), log.toString)
    }
  }

  test("a device path is opened as it is") {
    for {
      fake <- FakeWin32()
      _    <- opened(fake, """\\.\COM12""")
      log  <- fake.log.get
    } yield assert(log.contains("""open \\.\COM12"""), log.toString)
  }

  test(
    "a port that is not there is NoSuchPort, under the name that was given"
  ) {
    List(
      WindowsErrorName.ERROR_FILE_NOT_FOUND,
      WindowsErrorName.ERROR_PATH_NOT_FOUND
    ).traverse_ { name =>
      FakeWin32(openResult = Left(error(name)))
        .flatMap(opened(_))
        .assertEquals(Left(OpenError.NoSuchPort("COM3")), name.toString)
    }
  }

  test("access denied and a sharing violation both mean the port is in use") {
    List(
      WindowsErrorName.ERROR_ACCESS_DENIED,
      WindowsErrorName.ERROR_SHARING_VIOLATION
    ).traverse_ { name =>
      FakeWin32(openResult = Left(error(name)))
        .flatMap(opened(_))
        .assertEquals(Left(OpenError.PortBusy("COM3")), name.toString)
    }
  }

  test(
    "any other failure to open is an I/O error that keeps the Windows error"
  ) {
    FakeWin32(openResult = Left(error(WindowsErrorName.ERROR_GEN_FAILURE)))
      .flatMap(opened(_))
      .assertEquals(
        Left(
          OpenError.Io(
            "COM3",
            SystemError.Windows(error(WindowsErrorName.ERROR_GEN_FAILURE))
          )
        )
      )
  }

  test("a device that refuses the settings is InvalidSettings") {
    List(
      WindowsErrorName.ERROR_INVALID_PARAMETER,
      WindowsErrorName.ERROR_NOT_SUPPORTED,
      WindowsErrorName.ERROR_BAD_COMMAND,
      WindowsErrorName.ERROR_INVALID_FUNCTION
    ).traverse_ { name =>
      FakeWin32(configureResult = Left(error(name)))
        .flatMap(opened(_))
        .assertEquals(
          Left(
            OpenError.InvalidSettings("COM3", SystemError.Windows(error(name)))
          ),
          name.toString
        )
    }
  }

  test(
    "a configuration failure that is not about the settings is an I/O error"
  ) {
    FakeWin32(configureResult = Left(error(WindowsErrorName.ERROR_GEN_FAILURE)))
      .flatMap(opened(_))
      .assertEquals(
        Left(
          OpenError.Io(
            "COM3",
            SystemError.Windows(error(WindowsErrorName.ERROR_GEN_FAILURE))
          )
        )
      )
  }

  test(
    "the handle is closed when the Resource is released, whether or not configuring worked"
  ) {
    for {
      works <- FakeWin32()
      _     <- opened(works)
      fails <- FakeWin32(configureResult =
        Left(error(WindowsErrorName.ERROR_INVALID_PARAMETER))
      )
      _        <- opened(fails)
      worksLog <- works.log.get
      failsLog <- fails.log.get
    } yield {
      assertEquals(worksLog.count(_ == "close"), 1)
      assertEquals(failsLog.count(_ == "close"), 1)
    }
  }

  test("nothing is closed when the open itself fails: there is no handle") {
    for {
      fake <- FakeWin32(openResult =
        Left(error(WindowsErrorName.ERROR_FILE_NOT_FOUND))
      )
      _   <- opened(fake)
      log <- fake.log.get
    } yield assertEquals(log.count(_ == "close"), 0)
  }

  test("a path with a NUL in it is NoSuchPort, and nothing is opened") {
    val tricky = "COM3\u0000x"
    for {
      fake   <- FakeWin32()
      result <- opened(fake, tricky)
      log    <- fake.log.get
    } yield {
      assertEquals(result, Left(OpenError.NoSuchPort(tricky)))
      assertEquals(log.filter(_.startsWith("open")), Vector.empty)
    }
  }
}
