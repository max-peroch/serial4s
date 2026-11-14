package serial4s.errors

/** A Windows error: its `GetLastError` number and its name.
  *
  * Only the library builds one: the constructor is private to it, so a number
  * and a name that disagree cannot be made from outside. It mirrors [[Errno]].
  */
final case class WindowsError private[serial4s] (
    code: Int,
    name: WindowsErrorName
)
