package serial4s.errors

/** An operating-system error: its number and its name.
  *
  * The number differs between platforms (`EAGAIN` is 35 on macOS and 11 on
  * Linux), so compare and match on the name. Only the library builds one: the
  * constructor is private to it, so a number and a name that disagree cannot be
  * made from outside.
  */
final case class Errno private[serial4s] (code: Int, name: ErrnoName)
