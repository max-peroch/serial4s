package fs2.serial.posix

/** A POSIX file descriptor. */
private[serial] opaque type Fd = Int

private[serial] object Fd {

  def apply(value: Int): Fd = value

  extension (fd: Fd) {
    def value: Int = fd
  }
}
