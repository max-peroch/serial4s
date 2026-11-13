package fs2.serial.errors

/** What can go wrong when listing the serial ports of the machine. */
enum ListError {

  /** The device directory does not exist. */
  case NoSuchDirectory(path: String)

  /** The user may not read the device directory. */
  case AccessDenied(path: String)

  /** The directory could not be read, for another reason. `reason` is for a
    * person to read.
    */
  case Unreadable(path: String, reason: String)
}
