package serial4s.settings

import scala.concurrent.duration.FiniteDuration

/** Why a setting was refused before anything was touched. */
enum SettingError {

  /** The rate is outside [[BaudRate.Minimum]] to [[BaudRate.Maximum]]. */
  case BaudRateOutOfRange(value: Int)

  /** The interval is shorter than [[PollInterval.Minimum]]. */
  case PollIntervalTooShort(duration: FiniteDuration)
}
