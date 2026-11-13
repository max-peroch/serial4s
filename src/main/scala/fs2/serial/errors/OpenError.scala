package fs2.serial.errors

/** What can go wrong when opening a port. */
enum OpenError {

  /** `ENOENT`: nothing at that path. */
  case NoSuchPort(path: String)

  /** `EACCES`: the user may not open it (on Linux, usually not in the `dialout`
    * group).
    */
  case AccessDenied(path: String)

  /** Another process holds the exclusive lock on the port. */
  case PortBusy(path: String)

  /** The device refused the line settings, or it is not a serial device:
    * `EINVAL`, `ENOTTY` or `ENODEV` from the terminal calls.
    */
  case InvalidSettings(path: String, cause: SystemError)

  /** Any other failure reported by the operating system. */
  case Io(path: String, cause: SystemError)
}
