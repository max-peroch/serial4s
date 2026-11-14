package serial4s.win32

/** A Windows `HANDLE` to an open device: pointer-sized, so a `Long`. Its number
  * is reused once it is closed, which is why nothing may use one after release.
  */
private[serial4s] opaque type Handle = Long

private[serial4s] object Handle {

  def apply(value: Long): Handle = value

  extension (handle: Handle) {
    def value: Long = handle
  }
}
