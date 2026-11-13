package fs2.serial.win32

/** What ended a wait on an operation. */
private[serial] enum Awaited {

  /** The operation finished. */
  case Finished

  /** The wake event was signalled first. */
  case Woken
}
