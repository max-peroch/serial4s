package serial4s.backend.posix

import serial4s.errors.{Errno, ErrnoName, OpenError, SystemError}
import serial4s.platform.{Linux, MacOs, Platform}
import serial4s.posix.{Fd, IoBuffer, Posix, ReadOutcome, Selection, WaitLimit}
import serial4s.settings.SerialSettings
import serial4s.testing.PosixFixture
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite

final class PortOpenerSuite extends CatsEffectSuite with PosixFixture {

  /** A buffer that holds nothing: these tests never read or write. */
  private final class NoBuffer(val size: Int) extends IoBuffer[IO] {
    def fill(bytes: Array[Byte], from: Int, count: Int) = IO.unit
    def bytes(count: Int)            = IO.pure(Array.emptyByteArray)
    def readFrom(fd: Fd, count: Int) = IO.pure(Right(ReadOutcome.EndOfFile))
    def writeTo(fd: Fd, count: Int)  = IO.pure(Right(count))
  }

  /** A [[Posix]] that fails where told to, and records what was closed. */
  private final class Scripted(
      opens: Either[Errno, Fd],
      locks: Either[Errno, Unit],
      configures: Either[Errno, Unit],
      closed: Ref[IO, List[Fd]]
  ) extends Posix[IO] {
    def open(path: String): IO[Either[Errno, Fd]] = IO.pure(opens)
    def close(fd: Fd): IO[Either[Errno, Unit]]    =
      closed.update(_ :+ fd).as(Right(()))
    def lock(fd: Fd): IO[Either[Errno, Unit]]       = IO.pure(locks)
    def configure(fd: Fd, settings: SerialSettings) = IO.pure(configures)
    def readSettings(fd: Fd): IO[Either[Errno, SerialSettings]] =
      IO.pure(Right(SerialSettings()))
    def buffer(size: Int): Resource[IO, IoBuffer[IO]] =
      Resource.pure(new NoBuffer(size))
    def pipe(): IO[Either[Errno, (Fd, Fd)]] = IO.pure(Right((Fd(8), Fd(9))))
    def select(read: List[Fd], write: List[Fd], limit: WaitLimit) =
      IO.pure(Right(Selection(Nil, Nil)))
  }

  private val descriptor = Fd(7)

  /** The scripted posix, and the descriptors it was asked to close. */
  private def scripted(
      opens: Either[Errno, Fd] = Right(descriptor),
      locks: Either[Errno, Unit] = Right(()),
      configures: Either[Errno, Unit] = Right(())
  ): IO[(Posix[IO], IO[List[Fd]])] =
    Ref
      .of[IO, List[Fd]](Nil)
      .map(closed =>
        (new Scripted(opens, locks, configures, closed), closed.get)
      )

  private def err(name: ErrnoName): Errno = Errno(1, name)

  private val path = "/dev/cu.test"

  private def open(posix: Posix[IO]): IO[Either[OpenError, Fd]] =
    PortOpener.open(posix, path, SerialSettings()).use(IO.pure)

  test("open errors map to the scoped OpenError") {
    List(
      ErrnoName.ENOENT  -> OpenError.NoSuchPort(path),
      ErrnoName.ENOTDIR -> OpenError.NoSuchPort(path),
      ErrnoName.EACCES  -> OpenError.AccessDenied(path),
      ErrnoName.EPERM   -> OpenError.AccessDenied(path),
      ErrnoName.EBUSY   -> OpenError.PortBusy(path),
      ErrnoName.EMFILE  -> OpenError.Io(
        path,
        SystemError.Posix(err(ErrnoName.EMFILE))
      )
    ).traverse_ { case (name, error) =>
      scripted(opens = Left(err(name))).flatMap { case (posix, _) =>
        open(posix).assertEquals(Left(error), name.toString)
      }
    }
  }

  test(
    "a lock held elsewhere is a busy port, any other lock failure is an I/O error"
  ) {
    scripted(locks = Left(err(ErrnoName.EAGAIN))).flatMap { case (posix, _) =>
      open(posix).assertEquals(Left(OpenError.PortBusy(path)))
    } >> scripted(locks = Left(err(ErrnoName.EIO))).flatMap { case (posix, _) =>
      open(posix).assertEquals(
        Left(OpenError.Io(path, SystemError.Posix(err(ErrnoName.EIO))))
      )
    }
  }

  test(
    "a device that refuses the settings, or is not a terminal, is InvalidSettings"
  ) {
    List(ErrnoName.ENOTTY, ErrnoName.EINVAL, ErrnoName.ENODEV).traverse_ {
      name =>
        scripted(configures = Left(err(name))).flatMap { case (posix, _) =>
          open(posix).assertEquals(
            Left(OpenError.InvalidSettings(path, SystemError.Posix(err(name)))),
            name.toString
          )
        }
    } >> scripted(configures = Left(err(ErrnoName.EIO))).flatMap {
      case (posix, _) =>
        open(posix).assertEquals(
          Left(OpenError.Io(path, SystemError.Posix(err(ErrnoName.EIO))))
        )
    }
  }

  test(
    "the descriptor is open while the Resource is used and closed when it is released, whatever happened"
  ) {
    for {
      lockFails      <- scripted(locks = Left(err(ErrnoName.EAGAIN)))
      configureFails <- scripted(configures = Left(err(ErrnoName.ENOTTY)))
      works          <- scripted()
      cases = List(lockFails, configureFails, works)
      whileUsed <- cases.traverse { case (posix, closed) =>
        PortOpener.open(posix, path, SerialSettings()).use(_ => closed)
      }
      afterwards <- cases.traverse(_._2)
      opened     <- open(works._1)
    } yield {
      assertEquals(whileUsed, List(Nil, Nil, Nil))
      assertEquals(afterwards, List.fill(3)(List(descriptor)))
      assertEquals(opened, Right(descriptor))
    }
  }

  test("a path with a NUL in it is NoSuchPort, and nothing is opened") {
    val tricky = "/dev/cu.a\u0000/etc/passwd"
    scripted().flatMap { case (posix, closed) =>
      PortOpener
        .open(posix, tricky, SerialSettings())
        .use(IO.pure)
        .assertEquals(Left(OpenError.NoSuchPort(tricky))) >>
        closed.assertEquals(Nil)
    }
  }

  test("nothing is closed when the open itself fails: there is no descriptor") {
    scripted(opens = Left(err(ErrnoName.ENOENT))).flatMap {
      case (posix, closed) =>
        open(posix) >> closed.assertEquals(Nil)
    }
  }

  test("a path that does not exist is NoSuchPort") {
    val missing = "/dev/does-not-exist-serial4s"
    PortOpener
      .open(posix, missing, SerialSettings())
      .use(IO.pure)
      .assertEquals(Left(OpenError.NoSuchPort(missing)))
  }

  test("a file the user may not open is AccessDenied") {
    assume(System.getProperty("user.name") != "root", "root can open anything")
    IO.blocking(
      Files.createTempFile(
        "serial4s",
        ".port",
        PosixFilePermissions.asFileAttribute(
          PosixFilePermissions.fromString("---------")
        )
      )
    ).flatMap { file =>
      PortOpener
        .open(posix, file.toString, SerialSettings())
        .use(IO.pure)
        .guarantee(IO.blocking(Files.delete(file)))
        .assertEquals(Left(OpenError.AccessDenied(file.toString)))
    }
  }

  private def refusedWith(target: String): IO[Either[ErrnoName, String]] =
    PortOpener.open(posix, target, SerialSettings()).use(IO.pure).map {
      case Left(OpenError.InvalidSettings(_, SystemError.Posix(errno))) =>
        Left(errno.name)
      case other => Right(s"unexpected: $other")
    }

  test(
    "a regular file is InvalidSettings with ENOTTY: it locks, but is not a terminal"
  ) {
    IO.blocking(Files.createTempFile("serial4s", ".txt"))
      .flatMap { file =>
        refusedWith(file.toString).guarantee(IO.blocking(Files.delete(file)))
      }
      .assertEquals(Left(ErrnoName.ENOTTY))
  }

  test(
    "/dev/null is InvalidSettings: the terminal calls refuse it (ENODEV on macOS, ENOTTY on Linux)"
  ) {
    val expected = Platform.detect() match {
      case Right(MacOs) => ErrnoName.ENODEV
      case Right(Linux) => ErrnoName.ENOTTY
      case other        => fail(s"no expectation for $other")
    }
    refusedWith("/dev/null").assertEquals(Left(expected))
  }
}
