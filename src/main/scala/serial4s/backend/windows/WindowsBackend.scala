package serial4s.backend.windows

import serial4s.backend.{Backend, Device}
import serial4s.errors.{ListError, OpenError, WindowsError, WindowsErrorName}
import serial4s.settings.SerialSettings
import serial4s.win32.{DosDevices, Win32}
import cats.effect.{Concurrent, Resource}
import cats.syntax.all.*

/** The [[Backend]] for Windows: overlapped I/O on COM ports. */
private[serial4s] final class WindowsBackend[F[_]](win32: Win32[F])(using
    F: Concurrent[F]
) extends Backend[F] {

  def open(
      path: String,
      settings: SerialSettings
  ): Resource[F, Either[OpenError, Device[F]]] =
    WindowsDevice.open[F](win32, path, settings)

  /** The COM ports, as `COM3`: the name a person knows, which `open` accepts.
    */
  def list: F[Either[ListError, List[String]]] =
    win32.dosDevices.map(
      _.bimap(WindowsBackend.listError, DosDevices.comPorts)
    )
}

private[serial4s] object WindowsBackend {

  private val where = "QueryDosDevice"

  def listError(error: WindowsError): ListError = error.name match {
    case WindowsErrorName.ERROR_ACCESS_DENIED => ListError.AccessDenied(where)
    case _                                    =>
      ListError.Unreadable(
        where,
        s"Windows error ${error.code} (${error.name})"
      )
  }
}
