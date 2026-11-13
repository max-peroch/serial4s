package fs2.serial.win32

/** The `COMMTIMEOUTS` this library sets.
  *
  * Microsoft's documentation for `COMMTIMEOUTS`: with `ReadIntervalTimeout` and
  * `ReadTotalTimeoutMultiplier` both `MAXDWORD`, and `ReadTotalTimeoutConstant`
  * above zero and below `MAXDWORD`, a read returns at once with the bytes that
  * are already there, and otherwise waits for the first byte. That is the
  * "wait, then take what is there" of POSIX `select` plus `read`. The constant
  * is the longest wait, about 49 days: a read that times out returns no bytes,
  * and the device simply reads again.
  */
private[serial] object CommTimeouts {

  // struct _COMMTIMEOUTS: five DWORDs
  val Size: Int = 20

  val MaxDword: Int = -1 // 0xFFFFFFFF

  val ReadInterval: Int    = MaxDword
  val ReadMultiplier: Int  = MaxDword
  val ReadConstant: Int    = -2 // 0xFFFFFFFE
  val WriteMultiplier: Int = 0
  val WriteConstant: Int   = 0

  /** The five values, in the order of the struct. */
  val values: List[Int] =
    List(
      ReadInterval,
      ReadMultiplier,
      ReadConstant,
      WriteMultiplier,
      WriteConstant
    )
}
