package fs2.serial.ffi

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.syntax.all.*
import fs2.serial.errors.WindowsError
import fs2.serial.platform.NativeUnavailable
import fs2.serial.settings.SerialSettings
import fs2.serial.win32.{Event, Handle, Operation, Win32}

/** [[Win32]] over [[Kernel32]]: every call is suspended. Opening, closing and
  * configuring touch the device, so they run where blocking is allowed.
  */
private[serial] final class WindowsLib[F[_]] private (k: Kernel32)(using
    F: Sync[F]
) extends Win32[F] {

  def open(path: String): F[Either[WindowsError, Handle]] =
    F.blocking(k.createFile(path))

  def close(handle: Handle): F[Either[WindowsError, Unit]] =
    F.blocking(k.closeHandle(handle.value))

  def configure(
      handle: Handle,
      settings: SerialSettings
  ): F[Either[WindowsError, Unit]] = F.blocking(k.configure(handle, settings))

  def readSettings(handle: Handle): F[Either[WindowsError, SerialSettings]] =
    F.blocking(k.readSettings(handle))

  def event: Resource[F, Either[WindowsError, Event]] =
    Resource.make(F.delay(k.createEvent()))(
      _.traverse_(event => F.delay(k.closeHandle(event.value)).void)
    )

  def signal(event: Event): F[Unit] = failing(k.setEvent(event))

  def reset(event: Event): F[Unit] = failing(k.resetEvent(event))

  def operation(size: Int): Resource[F, Either[WindowsError, Operation[F]]] =
    Resource
      .make(F.delay(k.operation(size)))(
        _.traverse_(operation => F.delay(operation.close()))
      )
      .map(_.map(new WindowsOperation[F](_): Operation[F]))

  def dosDevices: F[Either[WindowsError, List[String]]] =
    F.blocking(k.dosDevices().map(fs2.serial.win32.DosDevices.parse))

  /** A call whose failure nobody can act on is a defect, not a value. */
  private def failing(call: => Either[WindowsError, Unit]): F[Unit] =
    F.delay(call)
      .flatMap(result => F.fromEither(result.left.map(WindowsFailure(_))))
}

private[serial] object WindowsLib {

  /** `kernel32` of this machine, or why it cannot be used. Loading is an
    * effect: it binds the symbols.
    */
  def load[F[_]](using F: Sync[F]): F[Either[NativeUnavailable, Win32[F]]] =
    F.delay(Kernel32.load().map(new WindowsLib[F](_)))
}
