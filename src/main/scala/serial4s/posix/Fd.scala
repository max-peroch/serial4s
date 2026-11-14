package serial4s.posix

/** A POSIX file descriptor. */
private[serial4s] opaque type Fd = Int

private[serial4s] object Fd {

  def apply(value: Int): Fd = value

  extension (fd: Fd) {
    def value: Int = fd
  }
}
