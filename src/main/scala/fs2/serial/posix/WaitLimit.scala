package fs2.serial.posix

import scala.concurrent.duration.FiniteDuration

/** How long `select` may wait. */
private[serial] enum WaitLimit {

  /** Until a descriptor is ready, however long that takes. */
  case Unlimited

  /** At most this long: an empty selection means the time ran out. */
  case UpTo(duration: FiniteDuration)
}
