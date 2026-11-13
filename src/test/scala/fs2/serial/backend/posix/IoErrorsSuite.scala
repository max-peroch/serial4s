package fs2.serial.backend.posix

import fs2.serial.errors.{Errno, ErrnoName, ReadError, SystemError, WriteError}
import munit.FunSuite

final class IoErrorsSuite extends FunSuite {

  private def errno(name: ErrnoName): Errno = Errno(1, name)

  test("the errnos that mean the device is gone are Disconnected on read") {
    List(ErrnoName.EIO, ErrnoName.ENXIO, ErrnoName.ENODEV).foreach { name =>
      assertEquals(IoErrors.read(errno(name)), ReadError.Disconnected, name)
    }
  }

  test("EPIPE also means gone on write, but not on read") {
    assertEquals(
      IoErrors.write(errno(ErrnoName.EPIPE)),
      WriteError.Disconnected
    )
    assertEquals(
      IoErrors.read(errno(ErrnoName.EPIPE)),
      ReadError.Io(SystemError.Posix(errno(ErrnoName.EPIPE)))
    )
  }

  test("any other errno is kept as an I/O error") {
    assertEquals(
      IoErrors.read(errno(ErrnoName.EFAULT)),
      ReadError.Io(SystemError.Posix(errno(ErrnoName.EFAULT)))
    )
    assertEquals(
      IoErrors.write(errno(ErrnoName.EMFILE)),
      WriteError.Io(SystemError.Posix(errno(ErrnoName.EMFILE)))
    )
    assertEquals(
      IoErrors.read(errno(ErrnoName.Unknown)),
      ReadError.Io(SystemError.Posix(errno(ErrnoName.Unknown)))
    )
  }
}
