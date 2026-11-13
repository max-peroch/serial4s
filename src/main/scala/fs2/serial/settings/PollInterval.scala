package fs2.serial.settings

import scala.concurrent.duration.*

/** How often [[Serial.watch]] looks at the device directory.
  *
  * At least [[PollInterval.Minimum]]: a zero or tiny interval would turn the
  * watcher into a hot loop over the file system.
  */
opaque type PollInterval = FiniteDuration

object PollInterval {

  /** The shortest interval accepted. */
  val Minimum: FiniteDuration = 10.millis

  val OneSecond: PollInterval = 1.second

  def apply(duration: FiniteDuration): Either[SettingError, PollInterval] =
    if (duration >= Minimum) Right(duration)
    else Left(SettingError.PollIntervalTooShort(duration))

  extension (interval: PollInterval) {
    def duration: FiniteDuration = interval
  }
}
