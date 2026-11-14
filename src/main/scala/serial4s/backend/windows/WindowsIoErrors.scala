package serial4s.backend.windows

import serial4s.errors.{
  ReadError,
  SystemError,
  WindowsError,
  WindowsErrorName,
  WriteError
}

/** Which Windows error means the device is gone, and which is a plain I/O
  * failure.
  *
  * Not yet checked against real drivers: an unplugged USB serial adapter is
  * reported differently by different drivers. These four are the ones usually
  * seen; a run with a real device is what confirms them.
  */
private[serial4s] object WindowsIoErrors {

  private def gone(error: WindowsError): Boolean = error.name match {
    case WindowsErrorName.ERROR_DEVICE_NOT_CONNECTED |
        WindowsErrorName.ERROR_GEN_FAILURE |
        WindowsErrorName.ERROR_ACCESS_DENIED |
        WindowsErrorName.ERROR_BAD_COMMAND =>
      true
    case _ => false
  }

  def read(error: WindowsError): ReadError =
    if (gone(error)) ReadError.Disconnected
    else ReadError.Io(SystemError.Windows(error))

  def write(error: WindowsError): WriteError =
    if (gone(error)) WriteError.Disconnected
    else WriteError.Io(SystemError.Windows(error))
}
