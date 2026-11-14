package serial4s.win32

import serial4s.errors.{WindowsError, WindowsErrorName}

/** The Windows errors this library tells apart, with their numbers from
  * `winerror.h` (mingw-w64's copy of the SDK header).
  */
private[serial4s] object WindowsErrors {

  val numbers: Map[WindowsErrorName, Int] = Map(
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

  private val names: Map[Int, WindowsErrorName] = numbers.map(_.swap)

  /** The error for a `GetLastError` number. */
  def named(code: Int): WindowsError =
    WindowsError(code, names.getOrElse(code, WindowsErrorName.Unknown))

  /** The error for a name this library knows. */
  def of(name: WindowsErrorName): WindowsError =
    WindowsError(numbers.getOrElse(name, -1), name)
}
