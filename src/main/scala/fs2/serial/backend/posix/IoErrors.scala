package fs2.serial.backend.posix

import fs2.serial.errors.{Errno, ErrnoName, ReadError, SystemError, WriteError}

/** Which `errno` means the device is gone, and which is a plain I/O failure. */
private[serial] object IoErrors {

  def read(errno: Errno): ReadError = errno.name match {
    case ErrnoName.EIO | ErrnoName.ENXIO | ErrnoName.ENODEV =>
      ReadError.Disconnected
    case _ => ReadError.Io(SystemError.Posix(errno))
  }

  def write(errno: Errno): WriteError = errno.name match {
    case ErrnoName.EIO | ErrnoName.ENXIO | ErrnoName.ENODEV | ErrnoName.EPIPE =>
      WriteError.Disconnected
    case _ => WriteError.Io(SystemError.Posix(errno))
  }
}
