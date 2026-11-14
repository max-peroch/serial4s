package serial4s.win32

/** What ended a wait on an operation. */
private[serial4s] enum Awaited {

  /** The operation finished. */
  case Finished

  /** The wake event was signalled first. */
  case Woken
}
