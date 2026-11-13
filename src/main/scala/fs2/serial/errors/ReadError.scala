package fs2.serial.errors

/** What can go wrong when reading from an open port. */
enum ReadError {

  /** The device went away (end of file, or `EIO`/`ENXIO`/`ENODEV`). */
  case Disconnected

  /** The port was closed: its `Resource` was released while this read was
    * pending or after it.
    */
  case Closed

  /** Any other failure reported by the operating system. */
  case Io(cause: SystemError)
}
