package serial4s.backend.posix

import serial4s.backend.{Backend, Device}
import serial4s.discovery.PortLister
import serial4s.errors.{ListError, OpenError}
import serial4s.platform.Platform
import serial4s.posix.Posix
import serial4s.settings.SerialSettings
import cats.effect.{Async, Resource}
import fs2.io.file.Files

/** The [[Backend]] for macOS and Linux: the POSIX calls, and the device names
  * of the [[Platform]].
  */
private[serial4s] final class PosixBackend[F[_]](
    posix: Posix[F],
    platform: Platform
)(using F: Async[F], files: Files[F])
    extends Backend[F] {

  def open(
      path: String,
      settings: SerialSettings
  ): Resource[F, Either[OpenError, Device[F]]] =
    PosixDevice.open[F](posix, path, settings)

  def list: F[Either[ListError, List[String]]] = PortLister.list[F](platform)
}
