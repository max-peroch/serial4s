package serial4s.posix

import cats.effect.Resource
import serial4s.errors.Errno
import serial4s.settings.SerialSettings

/** The few system calls the library needs, as values: each method describes a
  * call and does nothing until the `F` is run. The calls block, so an
  * implementation runs them on the blocking pool.
  *
  * A failure is the `Errno` the call left behind, as a value; `EINTR` is
  * retried inside. This is the one seam in front of the operating system: tests
  * that must not touch it implement this trait.
  */
private[serial4s] trait Posix[F[_]] {

  /** Opens `path` read-write, non-blocking, without becoming its controlling
    * terminal.
    */
  def open(path: String): F[Either[Errno, Fd]]

  def close(fd: Fd): F[Either[Errno, Unit]]

  /** Takes the exclusive advisory lock, without waiting. Closing the descriptor
    * releases it.
    */
  def lock(fd: Fd): F[Either[Errno, Unit]]

  /** Puts the line in raw mode with these settings and discards pending input
    * and output.
    */
  def configure(fd: Fd, settings: SerialSettings): F[Either[Errno, Unit]]

  /** The settings the line has now. Fails with `EINVAL` if its speed is outside
    * the accepted range.
    */
  def readSettings(fd: Fd): F[Either[Errno, SerialSettings]]

  /** `size` bytes of native memory for `read` and `write`, freed when the
    * `Resource` is released.
    */
  def buffer(size: Int): Resource[F, IoBuffer[F]]

  /** A pipe, both ends non-blocking: (read end, write end). */
  def pipe(): F[Either[Errno, (Fd, Fd)]]

  /** Waits until a descriptor is ready, or for as long as `limit` allows. */
  def select(
      read: List[Fd],
      write: List[Fd],
      limit: WaitLimit
  ): F[Either[Errno, Selection]]
}
