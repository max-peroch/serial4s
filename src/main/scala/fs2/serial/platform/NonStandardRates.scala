package fs2.serial.platform

/** How a platform sets a rate that has no `B` constant. */
private[serial] enum NonStandardRates {

  /** `cfsetspeed` takes the number itself: glibc 2.42 and later, on Linux. */
  case ThroughTermios

  /** A device ioctl after `tcsetattr`, with the speed passed by pointer:
    * `IOSSIOSPEED` on macOS. `request` is the ioctl number. A device that does
    * not implement it answers `ENOTTY`.
    */
  case ThroughIoctl(request: Long)
}
