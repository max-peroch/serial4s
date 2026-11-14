package serial4s.backend.windows

import serial4s.errors.{OpenError, SystemError, WindowsError, WindowsErrorName}
import serial4s.settings.SerialSettings
import serial4s.win32.{Handle, Win32, WindowsPath}
import cats.Monad
import cats.effect.Resource
import cats.syntax.all.*

/** Opens a port: open, then configure.
  *
  * The handle is a `Resource`, so it is closed on release however the opening
  * ended. There is no separate lock step: a handle opened without sharing is
  * the lock, and a second open of the same port fails.
  */
private[serial4s] object WindowsOpener {

  def open[F[_]](win32: Win32[F], path: String, settings: SerialSettings)(using
      F: Monad[F]
  ): Resource[F, Either[OpenError, Handle]] =
    // A NUL ends a C string, and a wide one: `COM3\0x` would open `COM3`.
    if (path.contains('\u0000')) Resource.pure(Left(OpenError.NoSuchPort(path)))
    else
      Resource
        .make(win32.open(WindowsPath.normalize(path)))(
          _.traverse_(win32.close(_).void)
        )
        .evalMap {
          case Left(error)   => F.pure(Left(openError(path, error)))
          case Right(handle) =>
            win32
              .configure(handle, settings)
              .map(_.bimap(configureError(path, _), _ => handle))
        }

  private def openError(path: String, error: WindowsError): OpenError =
    error.name match {
      case WindowsErrorName.ERROR_FILE_NOT_FOUND |
          WindowsErrorName.ERROR_PATH_NOT_FOUND =>
        OpenError.NoSuchPort(path)
      // A COM port has no per-user permissions, so "access denied" means that
      // another process has it open. Not yet seen on a real machine.
      case WindowsErrorName.ERROR_ACCESS_DENIED |
          WindowsErrorName.ERROR_SHARING_VIOLATION =>
        OpenError.PortBusy(path)
      case _ => OpenError.Io(path, SystemError.Windows(error))
    }

  private def configureError(path: String, error: WindowsError): OpenError =
    error.name match {
      case WindowsErrorName.ERROR_INVALID_PARAMETER |
          WindowsErrorName.ERROR_NOT_SUPPORTED |
          WindowsErrorName.ERROR_BAD_COMMAND |
          WindowsErrorName.ERROR_INVALID_FUNCTION =>
        OpenError.InvalidSettings(path, SystemError.Windows(error))
      case _ => OpenError.Io(path, SystemError.Windows(error))
    }
}
