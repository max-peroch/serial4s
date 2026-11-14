package serial4s.win32

/** The path to give `CreateFileW` for a port. */
private[serial4s] object WindowsPath {

  /** `COM3` becomes `\\.\COM3`. The prefix is required for `COM10` and above,
    * and works for every port, so it is always added. A path that already
    * starts with `\\.\` or `\\?\` is left alone.
    */
  def normalize(path: String): String =
    if (path.startsWith("""\\.\""") || path.startsWith("""\\?\""")) path
    else """\\.\""" + path
}
