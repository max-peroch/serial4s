package serial4s.platform

/** The native layer cannot be used here: an unsupported platform, or a libc
  * symbol is missing.
  */
private[serial4s] final case class NativeUnavailable(reason: String)
