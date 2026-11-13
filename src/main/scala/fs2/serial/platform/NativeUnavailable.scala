package fs2.serial.platform

/** The native layer cannot be used here: an unsupported platform, or a libc
  * symbol is missing.
  */
private[serial] final case class NativeUnavailable(reason: String)
