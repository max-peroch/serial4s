package serial4s.errors

/** The Windows errors (`GetLastError`) this library tells apart, by their
  * symbolic name. A closed set, like [[ErrnoName]]; anything else is `Unknown`,
  * with its number kept in [[WindowsError]]. The numbers are in
  * `win32.WindowsErrors`, taken from `winerror.h`.
  */
enum WindowsErrorName {
  case ERROR_INVALID_FUNCTION, ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND,
    ERROR_ACCESS_DENIED, ERROR_INVALID_HANDLE, ERROR_BAD_COMMAND,
    ERROR_GEN_FAILURE, ERROR_SHARING_VIOLATION, ERROR_NOT_SUPPORTED,
    ERROR_INVALID_PARAMETER, ERROR_INSUFFICIENT_BUFFER,
    ERROR_OPERATION_ABORTED, ERROR_IO_INCOMPLETE, ERROR_IO_PENDING,
    ERROR_DEVICE_NOT_CONNECTED, ERROR_NOT_FOUND, Unknown
}
