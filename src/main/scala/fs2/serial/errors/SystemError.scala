package fs2.serial.errors

/** An error the operating system reported, as the family it comes from
  * describes it.
  *
  * The two families do not share a numbering or a vocabulary, so one type that
  * pretended to would be wrong on one of them. Match on the name, not the
  * number: the same condition has a different number on each platform.
  */
enum SystemError {

  /** A POSIX `errno`: macOS and Linux. */
  case Posix(errno: Errno)

  /** A Windows `GetLastError` code. */
  case Windows(error: WindowsError)
}
