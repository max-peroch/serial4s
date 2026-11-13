package fs2.serial.errors

/** Why the library cannot run on this machine. */
enum PlatformError {

  /** This operating system or architecture has no backend, or its C library
    * lacks a symbol the backend needs. `reason` is for a person to read.
    */
  case Unsupported(reason: String)
}
