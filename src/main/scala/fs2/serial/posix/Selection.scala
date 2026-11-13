package fs2.serial.posix

/** The descriptors `select` reported ready, in the order they were asked for.
  */
private[serial] final case class Selection(
    readable: List[Fd],
    writable: List[Fd]
)
