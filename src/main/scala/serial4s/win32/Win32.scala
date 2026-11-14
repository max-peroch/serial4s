package serial4s.win32

import cats.effect.Resource
import serial4s.errors.WindowsError
import serial4s.settings.SerialSettings

/** The Windows calls the library needs, as values: each method describes a call
  * and does nothing until the `F` is run. The counterpart of `Posix`, and the
  * one seam in front of Windows: tests that must not touch it implement this
  * trait.
  *
  * A failure is the `WindowsError` that `GetLastError` reported, as a value.
  */
private[serial4s] trait Win32[F[_]] {

  /** Opens the device read-write for overlapped I/O, with exclusive access:
    * another open of the same port fails. `path` is what `CreateFileW` takes.
    */
  def open(path: String): F[Either[WindowsError, Handle]]

  def close(handle: Handle): F[Either[WindowsError, Unit]]

  /** Sets the line settings and the timeouts, and discards pending input and
    * output.
    */
  def configure(
      handle: Handle,
      settings: SerialSettings
  ): F[Either[WindowsError, Unit]]

  /** The settings the line has now. Fails with `ERROR_INVALID_PARAMETER` if it
    * is set to something this library cannot represent.
    */
  def readSettings(handle: Handle): F[Either[WindowsError, SerialSettings]]

  /** A manual-reset event, not signalled, closed when the `Resource` is
    * released.
    */
  def event: Resource[F, Either[WindowsError, Event]]

  /** Signals the event; it stays signalled until [[reset]]. */
  def signal(event: Event): F[Unit]

  def reset(event: Event): F[Unit]

  /** A transfer with `size` bytes of native memory, released with the
    * `Resource`.
    */
  def operation(size: Int): Resource[F, Either[WindowsError, Operation[F]]]

  /** The names of all DOS devices: `COM3` among them. */
  def dosDevices: F[Either[WindowsError, List[String]]]
}
