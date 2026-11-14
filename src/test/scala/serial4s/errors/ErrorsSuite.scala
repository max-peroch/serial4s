package serial4s.errors

import munit.FunSuite

final class ErrorsSuite extends FunSuite {

  test("errors compare by value, with the errno they carry") {
    val denied = Errno(13, ErrnoName.EACCES)
    assertEquals(
      OpenError.Io("/dev/x", SystemError.Posix(denied)),
      OpenError.Io("/dev/x", SystemError.Posix(Errno(13, ErrnoName.EACCES)))
    )
    assertNotEquals(
      OpenError.Io("/dev/x", SystemError.Posix(denied)),
      OpenError.Io("/dev/y", SystemError.Posix(denied))
    )
    assertNotEquals(
      ReadError.Io(SystemError.Posix(denied)),
      ReadError.Disconnected
    )
  }

  test("a stream failure carries the scoped error it wraps") {
    val denied = ListError.AccessDenied("/dev")
    assertEquals(SerialException.Listing(denied).error, denied)
    assertEquals(
      SerialException.Read(ReadError.Closed),
      SerialException.Read(ReadError.Closed)
    )
    assertNotEquals[SerialException, SerialException](
      SerialException.Read(ReadError.Closed),
      SerialException.Read(ReadError.Disconnected)
    )
  }

  test("a system error says which family it is from, and compares by value") {
    val posix   = SystemError.Posix(Errno(13, ErrnoName.EACCES))
    val windows = SystemError.Windows(
      WindowsError(5, WindowsErrorName.ERROR_ACCESS_DENIED)
    )
    assertEquals(posix, SystemError.Posix(Errno(13, ErrnoName.EACCES)))
    assertEquals(
      windows,
      SystemError.Windows(
        WindowsError(5, WindowsErrorName.ERROR_ACCESS_DENIED)
      )
    )
    assertNotEquals[SystemError, SystemError](posix, windows)
  }

  test(
    "the same condition has different numbers, so the name is what to match on"
  ) {
    val onMac   = SystemError.Posix(Errno(35, ErrnoName.EAGAIN))
    val onLinux = SystemError.Posix(Errno(11, ErrnoName.EAGAIN))
    assertNotEquals(onMac, onLinux)
    assertEquals(
      List(onMac, onLinux).collect { case SystemError.Posix(errno) =>
        errno.name
      },
      List(ErrnoName.EAGAIN, ErrnoName.EAGAIN)
    )
  }

  test("an I/O error carries its system error, on every operation") {
    val cause = SystemError.Windows(
      WindowsError(31, WindowsErrorName.ERROR_GEN_FAILURE)
    )
    assertEquals(OpenError.Io("COM3", cause).cause, cause)
    assertEquals(OpenError.InvalidSettings("COM3", cause).cause, cause)
    assertEquals(ReadError.Io(cause).cause, cause)
    assertEquals(WriteError.Io(cause).cause, cause)
  }

  test("a Windows error compares by its number and its name") {
    val denied = WindowsError(5, WindowsErrorName.ERROR_ACCESS_DENIED)
    assertEquals(denied, WindowsError(5, WindowsErrorName.ERROR_ACCESS_DENIED))
    assertNotEquals(
      denied,
      WindowsError(32, WindowsErrorName.ERROR_SHARING_VIOLATION)
    )
  }

  test(
    "a stream that could not open a port fails with the scoped error inside"
  ) {
    val error = OpenError.PortBusy("COM3")
    assertEquals(SerialException.Open(error).error, error)
    assertNotEquals[SerialException, SerialException](
      SerialException.Open(error),
      SerialException.Open(OpenError.NoSuchPort("COM3"))
    )
  }
}
