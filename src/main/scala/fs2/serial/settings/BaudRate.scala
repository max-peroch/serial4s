package fs2.serial.settings

/** A line speed, in bits per second, from [[BaudRate.Minimum]] to
  * [[BaudRate.Maximum]].
  *
  * The range is a guard, not a promise: a device may refuse a rate it cannot
  * do, and then opening the port fails with `OpenError.InvalidSettings`.
  *
  * A [[BaudRate.standard]] rate has a `B` constant in `termios.h` on every
  * platform. Any other rate is set the way the platform allows: on Linux with
  * glibc 2.42 or later through `termios` like the rest, and on macOS through
  * the `IOSSIOSPEED` ioctl, which only the drivers that implement it (USB
  * serial adapters, not pseudo-terminals) accept.
  */
opaque type BaudRate = Int

object BaudRate {

  /** The slowest rate accepted: the slowest standard one. */
  val Minimum: Int = 50

  /** The fastest rate accepted: the highest `B` constant the Linux kernel
    * defines.
    */
  val Maximum: Int = 4000000

  /** The standard rates, in ascending order: the ones `termios.h` defines as
    * `B50` to `B230400` on macOS (checked in `sys/termios.h`) and Linux. macOS
    * also defines the BSD-only 7200, 14400, 28800 and 76800, left out because
    * Linux has no such constant.
    */
  val standard: Vector[Int] = Vector(50, 75, 110, 134, 150, 200, 300, 600, 1200,
    1800, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400)

  val B9600: BaudRate   = 9600
  val B19200: BaudRate  = 19200
  val B38400: BaudRate  = 38400
  val B57600: BaudRate  = 57600
  val B115200: BaudRate = 115200
  val B230400: BaudRate = 230400

  def apply(value: Int): Either[SettingError, BaudRate] =
    if (value >= Minimum && value <= Maximum) Right(value)
    else Left(SettingError.BaudRateOutOfRange(value))

  extension (rate: BaudRate) {

    /** The rate in bits per second. */
    def value: Int = rate

    /** Whether the rate is one of [[standard]]. */
    def isStandard: Boolean = standard.contains(rate)
  }
}
