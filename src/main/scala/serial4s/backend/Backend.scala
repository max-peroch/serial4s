package serial4s.backend

import cats.effect.Resource
import serial4s.errors.{ListError, OpenError}
import serial4s.settings.SerialSettings

/** What an operating system provides: opening a device, and finding the devices
  * that exist.
  *
  * This is the seam between the library and an operating system. macOS and
  * Linux share one implementation over the POSIX calls; Windows will have its
  * own, over overlapped I/O.
  */
private[serial4s] trait Backend[F[_]] {

  /** Opens and configures `path`, and releases it when the `Resource` closes.
    * What the caller can act on comes back as the `Left`, and then there is
    * nothing to release.
    */
  def open(
      path: String,
      settings: SerialSettings
  ): Resource[F, Either[OpenError, Device[F]]]

  /** The serial devices of this machine, as paths to give to [[open]], in
    * order.
    */
  def list: F[Either[ListError, List[String]]]
}
