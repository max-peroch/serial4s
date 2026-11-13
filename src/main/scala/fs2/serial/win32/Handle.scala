package fs2.serial.win32

/** A Windows `HANDLE` to an open device: pointer-sized, so a `Long`. Its number
  * is reused once it is closed, which is why nothing may use one after release.
  */
private[serial] opaque type Handle = Long

private[serial] object Handle {

  def apply(value: Long): Handle = value

  extension (handle: Handle) {
    def value: Long = handle
  }
}
