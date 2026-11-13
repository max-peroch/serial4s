package fs2.serial.backend.posix

import fs2.serial.backend.{Backend, Device}
import fs2.serial.discovery.PortLister
import fs2.serial.errors.{ListError, OpenError}
import fs2.serial.platform.Platform
import fs2.serial.posix.Posix
import fs2.serial.settings.SerialSettings
import cats.effect.{Async, Resource}
import fs2.io.file.Files

/** The [[Backend]] for macOS and Linux: the POSIX calls, and the device names
  * of the [[Platform]].
  */
private[serial] final class PosixBackend[F[_]](
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
