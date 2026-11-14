package serial4s.backend.posix

import serial4s.errors.{Errno, ErrnoName, ReadError, SystemError, WriteError}

/** Which `errno` means the device is gone, and which is a plain I/O failure. */
private[serial4s] object IoErrors {

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
