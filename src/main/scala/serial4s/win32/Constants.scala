package serial4s.win32

/** Windows constants this library passes to `kernel32`, from mingw-w64's copy
  * of the SDK headers (`winnt.h`, `fileapi.h`, `winbase.h`).
  */
private[serial4s] object Constants {

  val GenericRead: Int  = 0x80000000 // GENERIC_READ
  val GenericWrite: Int = 0x40000000 // GENERIC_WRITE

  val OpenExisting: Int       = 3          // OPEN_EXISTING
  val FileFlagOverlapped: Int = 0x40000000 // FILE_FLAG_OVERLAPPED

  /** `INVALID_HANDLE_VALUE`: `(HANDLE) -1`. */
  val InvalidHandle: Long = -1L

  /** `PURGE_TXABORT | PURGE_RXABORT | PURGE_TXCLEAR | PURGE_RXCLEAR`. */
  val PurgeAll: Int = 0x1 | 0x2 | 0x4 | 0x8

  /** `INFINITE`: `0xFFFFFFFF`. */
  val Infinite: Int = -1

  val WaitObject0: Int = 0  // WAIT_OBJECT_0
  val WaitFailed: Int  = -1 // WAIT_FAILED, 0xFFFFFFFF

  // struct _OVERLAPPED on a 64-bit target: four 8-byte fields
  val OverlappedSize: Int        = 32
  val OverlappedEventOffset: Int = 24
}
