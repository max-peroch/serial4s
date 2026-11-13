package fs2.serial.platform

/** The operating system family: which backend to use. */
private[serial] enum Os {
  case Mac, Linux, Windows
}

private[serial] object Os {

  /** The operating system this JVM runs on, if the library knows it. */
  def detect(): Either[NativeUnavailable, Os] =
    forSystem(Option(System.getProperty("os.name")).getOrElse("unknown"))

  /** The family for an `os.name`: `Mac OS X`, `Linux`, or `Windows 10`,
    * `Windows 11`...
    */
  def forSystem(osName: String): Either[NativeUnavailable, Os] =
    osName match {
      case "Mac OS X"                         => Right(Os.Mac)
      case "Linux"                            => Right(Os.Linux)
      case name if name.startsWith("Windows") => Right(Os.Windows)
      case other                              =>
        Left(NativeUnavailable(s"unsupported operating system: $other"))
    }
}
