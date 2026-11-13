package fs2.serial.testing

import cats.effect.{Deferred, IO, Resource}
import fs2.serial.errors.Errno
import fs2.serial.posix.{Fd, IoBuffer, Posix, Selection, WaitLimit}
import fs2.serial.settings.SerialSettings

/** A [[Posix]] that says when `select` is first called: the moment a read or a
  * write is waiting. A test waits for that instead of sleeping and hoping.
  */
private[serial] final class SelectSpy private (
    underlying: Posix[IO],
    entered: Deferred[IO, Unit]
) extends Posix[IO] {

  def open(path: String): IO[Either[Errno, Fd]] = underlying.open(path)
  def close(fd: Fd): IO[Either[Errno, Unit]]    = underlying.close(fd)
  def lock(fd: Fd): IO[Either[Errno, Unit]]     = underlying.lock(fd)
  def configure(fd: Fd, settings: SerialSettings): IO[Either[Errno, Unit]] =
    underlying.configure(fd, settings)
  def readSettings(fd: Fd): IO[Either[Errno, SerialSettings]] =
    underlying.readSettings(fd)
  def buffer(size: Int): Resource[IO, IoBuffer[IO]] = underlying.buffer(size)
  def pipe(): IO[Either[Errno, (Fd, Fd)]]           = underlying.pipe()

  def select(
      read: List[Fd],
      write: List[Fd],
      limit: WaitLimit
  ): IO[Either[Errno, Selection]] =
    entered.complete(()) *> underlying.select(read, write, limit)
}

private[serial] object SelectSpy {

  /** The spy, and an `IO` that finishes once `select` has been called. */
  def make(underlying: Posix[IO]): IO[(Posix[IO], IO[Unit])] =
    Deferred[IO, Unit].map(entered =>
      (new SelectSpy(underlying, entered), entered.get)
    )
}
