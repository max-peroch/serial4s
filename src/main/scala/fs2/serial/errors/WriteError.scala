package fs2.serial.errors

/** What can go wrong when writing to an open port. */
enum WriteError {

  /** The device went away (`EPIPE`, `EIO`, `ENXIO` or `ENODEV`). */
  case Disconnected

  /** The port was closed: its `Resource` was released while this write was
    * pending or after it.
    */
  case Closed

  /** Any other failure reported by the operating system. */
  case Io(cause: SystemError)
}
