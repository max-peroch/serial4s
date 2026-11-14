package serial4s.ffi

import cats.effect.Resource
import cats.effect.kernel.Sync
import serial4s.errors.Errno
import serial4s.platform.{NativeUnavailable, Platform}
import serial4s.posix.{Fd, IoBuffer, Posix, Selection, WaitLimit}
import serial4s.settings.SerialSettings

import java.lang.foreign.Arena

/** [[Posix]] over [[Syscalls]]: every call is suspended and runs on the
  * blocking pool.
  */
private[serial4s] final class Libc[F[_]] private (calls: Syscalls)(using
    F: Sync[F]
) extends Posix[F] {

  def open(path: String): F[Either[Errno, Fd]] = F.blocking(calls.open(path))

  def close(fd: Fd): F[Either[Errno, Unit]] = F.blocking(calls.close(fd))

  def lock(fd: Fd): F[Either[Errno, Unit]] = F.blocking(calls.lock(fd))

  def configure(fd: Fd, settings: SerialSettings): F[Either[Errno, Unit]] =
    F.blocking(calls.configure(fd, settings))

  def readSettings(fd: Fd): F[Either[Errno, SerialSettings]] =
    F.blocking(calls.readSettings(fd))

  /** Each buffer has an arena of its own, shared between threads, closed with
    * the buffer.
    */
  def buffer(size: Int): Resource[F, IoBuffer[F]] =
    Resource
      .make(F.delay(Arena.ofShared()))(arena => F.delay(arena.close()))
      .evalMap(arena =>
        F.delay(new SegmentBuffer[F](calls, arena.allocate(size.toLong)))
      )

  def pipe(): F[Either[Errno, (Fd, Fd)]] = F.blocking(calls.pipe())

  def select(
      read: List[Fd],
      write: List[Fd],
      limit: WaitLimit
  ): F[Either[Errno, Selection]] = F.blocking(calls.select(read, write, limit))
}

private[serial4s] object Libc {

  /** The C library of this machine, or why it cannot be used. Loading is an
    * effect: it binds the libc symbols.
    */
  def load[F[_]](platform: Platform)(using
      F: Sync[F]
  ): F[Either[NativeUnavailable, Posix[F]]] =
    F.delay(Syscalls.load(platform).map(new Libc[F](_)))
}
