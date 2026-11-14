package serial4s.platform

/** Where this program runs: the operating system family and the CPU
  * architecture, as the JVM names it (`os.arch`: `amd64`, `aarch64`...).
  */
private[serial4s] final case class Host(os: Os, architecture: String)

private[serial4s] object Host {

  def detect(): Either[NativeUnavailable, Host] =
    Os.detect()
      .map(os =>
        Host(os, Option(System.getProperty("os.arch")).getOrElse("unknown"))
      )
}
