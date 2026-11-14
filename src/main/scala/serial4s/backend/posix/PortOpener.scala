package serial4s.backend.posix

import serial4s.errors.{Errno, ErrnoName, OpenError, SystemError}
import serial4s.posix.{Fd, Posix}
import serial4s.settings.SerialSettings
import cats.Monad
import cats.data.EitherT
import cats.effect.Resource
import cats.syntax.all.*

/** Opens a port: open, lock, configure.
  *
  * The descriptor is a `Resource`, so it is closed on release however the
  * opening ended: done, failed or cancelled. A step that fails ends in the
  * `Left`; the descriptor, if there was one, stays open (unlocked) until the
  * `Resource` is released.
  */
private[serial4s] object PortOpener {

  def open[F[_]](posix: Posix[F], path: String, settings: SerialSettings)(using
      F: Monad[F]
  ): Resource[F, Either[OpenError, Fd]] =
    // A NUL ends a C string: `/etc/x\0/dev/ttyUSB0` would open `/etc/x`.
    if (path.contains('\u0000')) Resource.pure(Left(OpenError.NoSuchPort(path)))
    else
      Resource
        .make(posix.open(path))(_.traverse_(posix.close(_).void))
        .evalMap {
          case Left(errno) => F.pure(Left(openError(path, errno)))
          case Right(fd)   =>
            (for {
              _ <- EitherT(posix.lock(fd)).leftMap(lockError(path, _))
              _ <- EitherT(posix.configure(fd, settings)).leftMap(
                configureError(path, _)
              )
            } yield fd).value
        }

  private def openError(path: String, errno: Errno): OpenError =
    errno.name match {
      case ErrnoName.ENOENT | ErrnoName.ENOTDIR => OpenError.NoSuchPort(path)
      case ErrnoName.EACCES | ErrnoName.EPERM   => OpenError.AccessDenied(path)
      case ErrnoName.EBUSY                      => OpenError.PortBusy(path)
      case _ => OpenError.Io(path, SystemError.Posix(errno))
    }

  private def lockError(path: String, errno: Errno): OpenError =
    errno.name match {
      case ErrnoName.EAGAIN => OpenError.PortBusy(path)
      case _                => OpenError.Io(path, SystemError.Posix(errno))
    }

  private def configureError(path: String, errno: Errno): OpenError =
    errno.name match {
      // `ENODEV` is what `/dev/null` answers to the terminal calls on macOS.
      case ErrnoName.ENOTTY | ErrnoName.EINVAL | ErrnoName.ENODEV =>
        OpenError.InvalidSettings(path, SystemError.Posix(errno))
      case _ => OpenError.Io(path, SystemError.Posix(errno))
    }
}
