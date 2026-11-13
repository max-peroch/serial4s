package fs2.serial.ffi

import fs2.serial.errors.{Errno, ErrnoName, OpenError, SystemError}
import fs2.serial.platform.{Linux, NonStandardRates, Platform}
import fs2.serial.backend.posix.PortOpener
import fs2.serial.posix.{Fd, Selection, WaitLimit}
import fs2.serial.settings.{
  BaudRate,
  CharSize,
  FlowControl,
  Parity,
  SerialSettings,
  StopBits
}
import fs2.serial.testing.{Bytes, PosixFixture, PseudoTerminal}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.serial.testing.TestIO.ok
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** The real system calls, against a pseudo-terminal pair and a pipe. */
final class LibcSuite extends CatsEffectSuite with PosixFixture {

  private def withPty[A](use: PseudoTerminal.Pty => IO[A]): IO[A] =
    PseudoTerminal.resource(posix).use(use)

  private def rate(value: Int): BaudRate =
    BaudRate(value).fold(error => fail(s"bad rate: $error"), identity)

  private def port(
      path: String,
      settings: SerialSettings = SerialSettings()
  ): Resource[IO, Fd] =
    PortOpener
      .open(posix, path, settings)
      .evalMap(result =>
        IO.fromEither(result.left.map(e => new AssertionError(s"open: $e")))
      )

  private def text(bytes: Either[Errno, Array[Byte]]): Either[Errno, String] =
    bytes.map(new String(_))

  test("select reports a pipe readable only once a byte is written to it") {
    posix
      .pipe()
      .ok
      .flatMap { case (readEnd, writeEnd) =>
        for {
          before  <- posix.select(List(readEnd), Nil, WaitLimit.UpTo(50.millis))
          written <- Bytes.write(posix, writeEnd, Array[Byte](1))
          after   <- posix.select(List(readEnd), Nil, WaitLimit.UpTo(2.seconds))
          _       <- posix.close(readEnd) *> posix.close(writeEnd)
        } yield (before, written, after, readEnd)
      }
      .map { case (before, written, after, readEnd) =>
        assertEquals(before, Right(Selection(Nil, Nil)))
        assertEquals(written, Right(1))
        assertEquals(after, Right(Selection(List(readEnd), Nil)))
      }
  }

  test("select refuses a negative descriptor with EINVAL") {
    posix
      .select(List(Fd(-1)), Nil, WaitLimit.UpTo(10.millis))
      .map(_.left.map(_.name))
      .assertEquals(Left(ErrnoName.EINVAL))
  }

  test("select never reports a descriptor that is not open as ready") {
    // Linux answers EBADF. macOS ignores a descriptor past its table and times out: its bit stays
    // set in the bitmap, so reading the bits alone would report it readable.
    posix
      .select(List(Fd(5000)), Nil, WaitLimit.UpTo(10.millis))
      .map {
        case Left(errno)      => Left(errno.name)
        case Right(selection) => Right(selection)
      }
      .map(outcome =>
        assert(
          outcome == Left(ErrnoName.EBADF) || outcome == Right(
            Selection(Nil, Nil)
          ),
          s"unexpected: $outcome"
        )
      )
  }

  test("select works on a descriptor above FD_SETSIZE (1024)") {
    // Each pipe takes two descriptors, so 520 of them push the last past 1024.
    val pipes: IO[List[(Fd, Fd)]] =
      (1 to 520).toList.traverse(_ => posix.pipe().ok)
    Resource
      .make(pipes)(
        _.traverse_ { case (readEnd, writeEnd) =>
          posix.close(readEnd) *> posix.close(writeEnd).void
        }
      )
      .use { all =>
        val (readEnd, writeEnd) = all.last
        for {
          written <- Bytes.write(posix, writeEnd, Array[Byte](1))
          ready   <- posix.select(List(readEnd), Nil, WaitLimit.UpTo(2.seconds))
        } yield (readEnd.value, written, ready, readEnd)
      }
      .map { case (highest, written, ready, readEnd) =>
        assert(highest >= 1024, s"the test never got past 1024 (got $highest)")
        assertEquals(written, Right(1))
        assertEquals(ready, Right(Selection(List(readEnd), Nil)))
      }
  }

  test("a transfer of zero or fewer bytes is EINVAL, never end of file") {
    posix
      .pipe()
      .ok
      .flatMap { case (readEnd, writeEnd) =>
        posix
          .buffer(16)
          .use { buffer =>
            (
              buffer.readFrom(readEnd, 0),
              buffer.readFrom(readEnd, -1),
              buffer.writeTo(writeEnd, 0),
              buffer.writeTo(writeEnd, Int.MinValue)
            ).tupled
          }
          .guarantee(posix.close(readEnd) *> posix.close(writeEnd).void)
          .map { case (a, b, c, d) =>
            List(
              a.left.map(_.name),
              b.left.map(_.name),
              c.left.map(_.name),
              d.left.map(_.name)
            )
          }
      }
      .assertEquals(List.fill(4)(Left(ErrnoName.EINVAL)))
  }

  test("a buffer clamps what is copied in and out to what fits") {
    posix
      .buffer(4)
      .use { buffer =>
        for {
          _     <- buffer.fill(Array[Byte](1, 2, 3, 4, 5, 6), 0, 100)
          whole <- buffer.bytes(100)
          _     <- buffer.fill(
            Array[Byte](9, 9, 9),
            10,
            3
          ) // from is past the end: copies nothing
          unchanged <- buffer.bytes(4)
          none      <- buffer.bytes(-3)
        } yield (whole.toList, unchanged.toList, none.toList)
      }
      .assertEquals((List[Byte](1, 2, 3, 4), List[Byte](1, 2, 3, 4), Nil))
  }

  test("errno stays right when many threads make failing calls at once") {
    // One saved errno per thread: a shared one would let calls overwrite each other's.
    (1 to 600).toList
      .parTraverse { i =>
        if (i % 2 == 0) posix.close(Fd(-1)).map(_.left.map(_.name))
        else posix.open("/does-not-exist-fs2-serial").map(_.left.map(_.name))
      }
      .map { outcomes =>
        val expected =
          (1 to 600).toList.map(i =>
            Left(if (i % 2 == 0) ErrnoName.EBADF else ErrnoName.ENOENT)
          )
        assertEquals(outcomes, expected)
      }
  }

  test("bytes written to the master are read from the opened port") {
    withPty { pty =>
      port(pty.slavePath).use { port =>
        for {
          written <- Bytes.write(posix, pty.master, "hello".getBytes)
          ready   <- posix.select(List(port), Nil, WaitLimit.UpTo(2.seconds))
          read    <- Bytes.read(posix, port, 64)
        } yield (written, ready, text(read), port)
      }
    }.map { case (written, ready, read, port) =>
      assertEquals(written, Right(5))
      assertEquals(ready, Right(Selection(List(port), Nil)))
      assertEquals(read, Right("hello"))
    }
  }

  test("bytes written to the port arrive at the master") {
    withPty { pty =>
      port(pty.slavePath).use { port =>
        for {
          written <- Bytes.write(posix, port, "world".getBytes)
          ready   <- posix.select(
            List(pty.master),
            Nil,
            WaitLimit.UpTo(2.seconds)
          )
          read <- Bytes.read(posix, pty.master, 64)
        } yield (written, ready, text(read), pty.master)
      }
    }.map { case (written, ready, read, master) =>
      assertEquals(written, Right(5))
      assertEquals(ready, Right(Selection(List(master), Nil)))
      assertEquals(read, Right("world"))
    }
  }

  test(
    "reading a port with nothing to read fails with EAGAIN instead of blocking"
  ) {
    withPty { pty =>
      port(pty.slavePath).use(Bytes.read(posix, _, 64))
    }.map(_.left.map(_.name)).assertEquals(Left(ErrnoName.EAGAIN))
  }

  private val combinations: List[SerialSettings] = List(
    SerialSettings(),
    SerialSettings(
      rate(9600),
      CharSize.Seven,
      Parity.Even,
      StopBits.Two,
      FlowControl.Hardware
    ),
    SerialSettings(
      rate(57600),
      CharSize.Eight,
      Parity.Odd,
      StopBits.One,
      FlowControl.Software
    ),
    SerialSettings(
      rate(230400),
      CharSize.Six,
      Parity.NoParity,
      StopBits.One,
      FlowControl.Off
    ),
    SerialSettings(
      rate(1200),
      CharSize.Five,
      Parity.Odd,
      StopBits.Two,
      FlowControl.Off
    )
  )

  /** What a pseudo-terminal keeps of the settings. The Linux kernel forces a
    * pty to 8 data bits and no parity (`pty_set_termios` in
    * `drivers/tty/pty.c`: `c_cflag &= ~(CSIZE | PARENB)`, then `|= CS8`), so
    * those two cannot be read back there. Everything else is kept on both
    * platforms.
    */
  private def keptByPty(settings: SerialSettings): SerialSettings =
    Platform.detect() match {
      case Right(Linux) =>
        settings.copy(charSize = CharSize.Eight, parity = Parity.NoParity)
      case _ => settings
    }

  combinations.foreach { settings =>
    test(s"settings read back unchanged: $settings") {
      withPty { pty =>
        port(pty.slavePath, settings).use(posix.readSettings)
      }.assertEquals(Right(keptByPty(settings)))
    }
  }

  test(
    "a non-standard rate: a macOS pty refuses it with ENOTTY, a Linux pty takes it"
  ) {
    val odd = SerialSettings(rate(12345))
    withPty { pty =>
      PortOpener
        .open(posix, pty.slavePath, odd)
        .use {
          case Right(fd)   => posix.readSettings(fd).map(Right(_))
          case Left(error) => IO.pure(Left(error))
        }
        .map(outcome => (outcome, pty.slavePath))
    }.map { case (outcome, path) =>
      Platform.detect().map(_.nonStandardRates) match {
        case Right(NonStandardRates.ThroughIoctl(_)) =>
          assertEquals(
            outcome,
            Left(
              OpenError.InvalidSettings(
                path,
                SystemError.Posix(Errno(25, ErrnoName.ENOTTY))
              )
            )
          )
        case Right(NonStandardRates.ThroughTermios) =>
          assertEquals(outcome, Right(Right(odd)))
        case Left(unavailable) => fail(unavailable.reason)
      }
    }
  }

  test(
    "a second open of a port in use is refused, and works once the first is closed"
  ) {
    withPty { pty =>
      for {
        second <- port(pty.slavePath).use(_ =>
          PortOpener.open(posix, pty.slavePath, SerialSettings()).use(IO.pure)
        )
        third <- PortOpener
          .open(posix, pty.slavePath, SerialSettings())
          .use(result => IO.pure(result.isRight))
      } yield (second, third, pty.slavePath)
    }.map { case (second, thirdOpened, path) =>
      assertEquals(second, Left(OpenError.PortBusy(path)))
      assert(thirdOpened, "expected the port to be free again")
    }
  }
}
