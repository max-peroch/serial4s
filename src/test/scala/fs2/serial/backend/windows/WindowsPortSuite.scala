package fs2.serial.backend.windows

import fs2.serial.Serial
import fs2.serial.errors.{OpenError, ReadError}
import fs2.serial.ffi.WindowsLib
import fs2.serial.port.SerialPort
import fs2.serial.settings.*
import fs2.serial.win32.{Handle, Win32}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Tests against a real device on a COM port. Opt-in: nothing is opened unless
  * the port is named in the environment, and only on Windows.
  *
  * {{{
  * set FS2_SERIAL_TEST_PORT=COM3
  * set FS2_SERIAL_TEST_BAUD=9600        (optional; 9600 by default)
  * sbt "testOnly *WindowsPortSuite"
  * }}}
  *
  * Only harmless commands are sent: `AT` and `ATI`. Nothing dials (`ATD`),
  * answers (`ATA`) or stores a profile (`AT&W`), so the device is left as it
  * was found.
  */
final class WindowsPortSuite extends CatsEffectSuite {

  override def munitIOTimeout: Duration = 60.seconds

  private val onWindows: Boolean =
    Option(System.getProperty("os.name")).exists(_.startsWith("Windows"))

  private val configured: Option[String] =
    sys.env.get("FS2_SERIAL_TEST_PORT").map(_.trim).filter(_.nonEmpty)

  private val baud: Int =
    sys.env
      .get("FS2_SERIAL_TEST_BAUD")
      .flatMap(_.trim.toIntOption)
      .getOrElse(9600)

  private def settings(
      size: CharSize = CharSize.Eight,
      parity: Parity = Parity.NoParity,
      stop: StopBits = StopBits.One,
      flow: FlowControl = FlowControl.Off
  ): SerialSettings =
    SerialSettings(
      BaudRate(baud).fold(error => fail(s"bad rate: $error"), identity),
      size,
      parity,
      stop,
      flow
    )

  /** The port to use, or the test is skipped. */
  private def device: String = {
    assume(onWindows, "this machine is not Windows")
    configured.getOrElse {
      assume(
        false,
        "set FS2_SERIAL_TEST_PORT to the device's port, for example COM3"
      )
      ""
    }
  }

  private val serial: IO[Serial[IO]] =
    Serial
      .make[IO]
      .flatMap(
        _.fold(
          error => IO.raiseError(new AssertionError(s"make: $error")),
          IO.pure
        )
      )

  private def opened(
      config: SerialSettings = settings()
  ): Resource[IO, SerialPort[IO]] =
    Resource.eval(serial).flatMap(_.open(device, config)).evalMap {
      case Right(port) => IO.pure(port)
      case Left(error) =>
        IO.raiseError(new AssertionError(s"open $device: $error"))
    }

  /** Sends a command and gives everything that came back up to the first `OK`.
    */
  private def ask(port: SerialPort[IO], command: String): IO[String] =
    Stream
      .emits(s"$command\r".getBytes)
      .covary[IO]
      .through(port.duplex)
      .through(fs2.text.utf8.decode)
      .scan("")(_ + _)
      .find(_.contains("OK"))
      .timeout(5.seconds)
      .compile
      .lastOrError

  test("the device's port is listed") {
    val name = device
    serial
      .flatMap(_.list)
      .map(listed =>
        assert(listed.exists(_.contains(name.toUpperCase)), listed.toString)
      )
  }

  test("the device answers AT with OK") {
    val name = device
    opened()
      .use(ask(_, "AT"))
      .map(answer => assert(answer.contains("OK"), s"$name: $answer"))
  }

  test("ATI says what the device is (printed, for the maintainer)") {
    device
    opened()
      .use(ask(_, "ATI"))
      .flatMap(answer => IO.println(s"ATI -> ${answer.trim}"))
      .as(())
  }

  test("a read that is cancelled leaves the port usable") {
    device
    opened().use { port =>
      for {
        timedOut <- port.read.timeout(500.millis).attempt
        answer   <- ask(port, "AT")
      } yield {
        assert(
          timedOut.isLeft,
          "nothing should have arrived, so the read should time out"
        )
        assert(answer.contains("OK"), answer)
      }
    }
  }

  test("a read pending when the port is released ends with Closed") {
    device
    Serial.make[IO].flatMap {
      case Left(error) => IO.raiseError(new AssertionError(s"make: $error"))
      case Right(s)    =>
        s.open(device, settings()).allocated.flatMap {
          case (Right(port), release) =>
            for {
              reading <- port.read.start
              _       <- IO.sleep(
                300.millis
              ) // a real device: no way to observe the wait
              _      <- release
              result <- reading.joinWithNever.timeout(5.seconds)
            } yield assertEquals(result.map(_.size), Left(ReadError.Closed))
          case (Left(error), _) =>
            IO.raiseError(new AssertionError(s"open: $error"))
        }
    }
  }

  test(
    "a port that is open cannot be opened again, and can once it is released"
  ) {
    val name = device
    serial.flatMap { s =>
      for {
        busy <- s
          .open(name, settings())
          .use(_ => s.open(name, settings()).use(IO.pure))
        again <- s.open(name, settings()).use(opened => IO.pure(opened.isRight))
      } yield {
        assertEquals(busy.map(_ => ()), Left(OpenError.PortBusy(name)))
        assert(again, "the port should open again after it was released")
      }
    }
  }

  test(
    "opening and releasing the port twenty times leaks nothing that stops the next open"
  ) {
    val name = device
    serial
      .flatMap(s =>
        (1 to 20).toList.traverse(_ =>
          s.open(name, settings()).use(opened => IO.pure(opened.isRight))
        )
      )
      .map(results => assertEquals(results, List.fill(20)(true)))
  }

  test("a line setting is read back as it was set") {
    val name         = device
    val combinations = List(
      settings(),
      settings(CharSize.Seven, Parity.Even, StopBits.One),
      settings(CharSize.Eight, Parity.Odd, StopBits.Two),
      settings(flow = FlowControl.Hardware),
      settings(flow = FlowControl.Software)
    )
    WindowsLib
      .load[IO]
      .flatMap {
        case Left(unavailable) =>
          IO.raiseError(new AssertionError(unavailable.reason))
        case Right(win32) =>
          combinations.traverse { config =>
            roundTrip(win32, name, config).map(config -> _)
          }
      }
      .map { results =>
        results.foreach { case (config, readBack) =>
          assertEquals(readBack, Right(config), s"set $config")
        }
      }
  }

  /** Opens the port, sets the line, and reads the line back. */
  private def roundTrip(
      win32: Win32[IO],
      name: String,
      config: SerialSettings
  ) =
    Resource
      .make(win32.open("""\\.\""" + name))(
        _.traverse_(handle => win32.close(handle).void)
      )
      .use {
        case Left(error)   => IO.pure(Left(s"open: $error"))
        case Right(handle) => configureAndRead(win32, handle, config)
      }

  private def configureAndRead(
      win32: Win32[IO],
      handle: Handle,
      config: SerialSettings
  ) =
    win32.configure(handle, config).flatMap {
      case Left(error) => IO.pure(Left(s"configure: $error"))
      case Right(_)    =>
        win32.readSettings(handle).map(_.left.map(error => s"read: $error"))
    }
}
