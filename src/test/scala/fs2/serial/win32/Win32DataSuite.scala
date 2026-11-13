package fs2.serial.win32

import fs2.serial.errors.{WindowsError, WindowsErrorName}
import munit.FunSuite

final class Win32DataSuite extends FunSuite {

  test("the error numbers are the ones in winerror.h") {
    // read from mingw-w64's winerror.h
    val expected = List(
      WindowsErrorName.ERROR_INVALID_FUNCTION     -> 1,
      WindowsErrorName.ERROR_FILE_NOT_FOUND       -> 2,
      WindowsErrorName.ERROR_PATH_NOT_FOUND       -> 3,
      WindowsErrorName.ERROR_ACCESS_DENIED        -> 5,
      WindowsErrorName.ERROR_INVALID_HANDLE       -> 6,
      WindowsErrorName.ERROR_BAD_COMMAND          -> 22,
      WindowsErrorName.ERROR_GEN_FAILURE          -> 31,
      WindowsErrorName.ERROR_SHARING_VIOLATION    -> 32,
      WindowsErrorName.ERROR_NOT_SUPPORTED        -> 50,
      WindowsErrorName.ERROR_INVALID_PARAMETER    -> 87,
      WindowsErrorName.ERROR_INSUFFICIENT_BUFFER  -> 122,
      WindowsErrorName.ERROR_OPERATION_ABORTED    -> 995,
      WindowsErrorName.ERROR_IO_INCOMPLETE        -> 996,
      WindowsErrorName.ERROR_IO_PENDING           -> 997,
      WindowsErrorName.ERROR_DEVICE_NOT_CONNECTED -> 1167,
      WindowsErrorName.ERROR_NOT_FOUND            -> 1168
    )
    assertEquals(WindowsErrors.numbers, expected.toMap)
  }

  test("every error name but Unknown has a number, and no two share one") {
    val missing = WindowsErrorName.values
      .filter(_ != WindowsErrorName.Unknown)
      .filterNot(WindowsErrors.numbers.contains)
    assertEquals(missing.toList, Nil)
    assertEquals(
      WindowsErrors.numbers.values.toSet.size,
      WindowsErrors.numbers.size
    )
  }

  test(
    "a number gives its name, and one the library does not know keeps its number"
  ) {
    assertEquals(
      WindowsErrors.named(5),
      WindowsError(5, WindowsErrorName.ERROR_ACCESS_DENIED)
    )
    assertEquals(
      WindowsErrors.named(1234),
      WindowsError(1234, WindowsErrorName.Unknown)
    )
    assertEquals(
      WindowsErrors.of(WindowsErrorName.ERROR_IO_PENDING),
      WindowsError(997, WindowsErrorName.ERROR_IO_PENDING)
    )
  }

  test("a read waits for the first byte and times out after about 49 days") {
    // COMMTIMEOUTS, as Microsoft documents it: interval and multiplier MAXDWORD, constant in between
    assertEquals(CommTimeouts.Size, 20)
    assertEquals(CommTimeouts.values.size, 5)
    assertEquals(Integer.toUnsignedLong(CommTimeouts.ReadInterval), 0xffffffffL)
    assertEquals(
      Integer.toUnsignedLong(CommTimeouts.ReadMultiplier),
      0xffffffffL
    )
    val constant = Integer.toUnsignedLong(CommTimeouts.ReadConstant)
    assert(constant > 0L && constant < 0xffffffffL, constant.toString)
    assertEquals(
      (CommTimeouts.WriteMultiplier, CommTimeouts.WriteConstant),
      (0, 0)
    )
  }

  test("the constants are those of the Windows headers") {
    assertEquals(
      Integer.toUnsignedLong(Constants.GenericRead | Constants.GenericWrite),
      0xc0000000L
    )
    assertEquals(Constants.OpenExisting, 3)
    assertEquals(Constants.FileFlagOverlapped, 0x40000000)
    assertEquals(Constants.InvalidHandle, -1L)
    assertEquals(Constants.PurgeAll, 0xf)
    assertEquals(Integer.toUnsignedLong(Constants.Infinite), 0xffffffffL)
    assertEquals((Constants.WaitObject0, Constants.WaitFailed), (0, -1))
  }

  test("OVERLAPPED is four 8-byte fields, the event last") {
    assertEquals(Constants.OverlappedSize, 32)
    assertEquals(Constants.OverlappedEventOffset, Constants.OverlappedSize - 8)
  }

  test(
    "a port name gets the \\\\.\\ prefix, which every port accepts and COM10 and above need"
  ) {
    assertEquals(WindowsPath.normalize("COM3"), """\\.\COM3""")
    assertEquals(WindowsPath.normalize("COM12"), """\\.\COM12""")
    assertEquals(WindowsPath.normalize("com1"), """\\.\com1""")
  }

  test("a path that is already a device path is left alone") {
    assertEquals(WindowsPath.normalize("""\\.\COM3"""), """\\.\COM3""")
    assertEquals(WindowsPath.normalize("""\\?\COM3"""), """\\?\COM3""")
  }

  test("the double-NUL-terminated list of DOS devices is split into names") {
    assertEquals(
      DosDevices.parse("C:\u0000COM3\u0000NUL\u0000\u0000"),
      List("C:", "COM3", "NUL")
    )
    assertEquals(DosDevices.parse("\u0000\u0000"), Nil)
    assertEquals(DosDevices.parse(""), Nil)
  }

  test(
    "the COM ports are picked out and put in numeric order, so COM9 comes before COM10"
  ) {
    assertEquals(
      DosDevices.comPorts(
        List("C:", "COM10", "COM2", "COM9", "NUL", "COMX", "COM", "AUX", "COM1")
      ),
      List("COM1", "COM2", "COM9", "COM10")
    )
    assertEquals(DosDevices.comPorts(List("COM3", "COM3")), List("COM3"))
    assertEquals(DosDevices.comPorts(Nil), Nil)
  }
}
