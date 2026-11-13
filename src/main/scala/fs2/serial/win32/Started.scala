package fs2.serial.win32

/** How an overlapped `ReadFile` or `WriteFile` began. */
private[serial] enum Started {

  /** It finished at once: [[Operation.complete]] has the count. */
  case Done

  /** It is running: wait for it, or cancel it. */
  case Pending
}
