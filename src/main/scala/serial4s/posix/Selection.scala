package serial4s.posix

/** The descriptors `select` reported ready, in the order they were asked for.
  */
private[serial4s] final case class Selection(
    readable: List[Fd],
    writable: List[Fd]
)
