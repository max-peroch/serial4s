package serial4s.platform

import serial4s.errors.ErrnoName
import serial4s.settings.CharSize

/** macOS, x86_64. Every number below was printed by a C program built against
  * the macOS SDK headers (`sizeof`, `offsetof`, and the constants themselves).
  * `poll(2)` is not used: its man page says "The poll() system call currently
  * does not support devices".
  *
  * Not checked on Apple Silicon: the layouts are the same by the ABI, but
  * nobody has run it there.
  */
private[serial4s] object MacOs extends Platform {

  val name: String = "macOS"

  val portDirectory: String = "/dev"

  /** `cu.*` and `tty.*`, with the dot: seen in `/dev` on a Mac, where a
    * pseudo-terminal is `ttys001`.
    */
  def isSerialPortName(name: String): Boolean =
    name.startsWith("cu.") || name.startsWith("tty.")

  val errnos: Map[ErrnoName, Int] = Map(
    ErrnoName.EPERM   -> 1,
    ErrnoName.ENOENT  -> 2,
    ErrnoName.EINTR   -> 4,
    ErrnoName.EIO     -> 5,
    ErrnoName.ENXIO   -> 6,
    ErrnoName.EBADF   -> 9,
    ErrnoName.EACCES  -> 13,
    ErrnoName.EFAULT  -> 14,
    ErrnoName.EBUSY   -> 16,
    ErrnoName.ENODEV  -> 19,
    ErrnoName.ENOTDIR -> 20,
    ErrnoName.EINVAL  -> 22,
    ErrnoName.ENFILE  -> 23,
    ErrnoName.EMFILE  -> 24,
    ErrnoName.ENOTTY  -> 25,
    ErrnoName.EPIPE   -> 32,
    ErrnoName.EAGAIN  -> 35
  )

  val oRdwr: Int     = 2
  val oNonblock: Int = 4
  val oNoctty: Int   = 131072
  val oCloexec: Int  = 16777216
  val fGetfl: Int    = 3
  val fSetfl: Int    = 4
  val fSetfd: Int    = 2
  val fdCloexec: Int = 1
  val lockEx: Int    = 2
  val lockNb: Int    = 4

  val fdSetBytes: Int = 128
  // `select` refuses a descriptor of 1024 or more with EINVAL: `select$DARWIN_EXTSN` (what
  // `_DARWIN_UNLIMITED_SELECT` links to) takes a bigger bitmap. A C probe showed both.
  val selectSymbol: String = "select$DARWIN_EXTSN"
  val timevalSize: Int     = 16

  // struct timeval: `tv_sec` is 8 bytes at 0 and `tv_usec` is 4 bytes at 8.
  val timevalUsecOffset: Int  = 8
  val timevalUsecWidth: Width = Width.Bits32

  // struct termios: four 8-byte flag words, cc_t c_cc[20] at 32, then two 8-byte speeds.
  val termiosSize: Int = 72
  val flagWidth: Width = Width.Bits64
  val iflagOffset: Int = 0
  val cflagOffset: Int = 16
  val ccOffset: Int    = 32
  val vmin: Int        = 16
  val vtime: Int       = 17
  val tcsanow: Int     = 0
  val tcioflush: Int   = 3
  val csize: Long      = 768L
  val cstopb: Long     = 1024L
  val cread: Long      = 2048L
  val parenb: Long     = 4096L
  val parodd: Long     = 8192L
  val clocal: Long     = 32768L
  val crtscts: Long    = 196608L
  val ixon: Long       = 512L
  val ixoff: Long      = 1024L
  val ixany: Long      = 2048L

  def charSizeBits(size: CharSize): Long = size match {
    case CharSize.Five  => 0L
    case CharSize.Six   => 256L
    case CharSize.Seven => 512L
    case CharSize.Eight => 768L
  }

  // `speed_t` is an unsigned long, and on macOS a `B9600` constant is the number 9600 itself.
  val speedWidth: Width = Width.Bits64

  /** `IOSSIOSPEED` is `_IOW('T', 2, speed_t)`: 0x80085402, printed by a C
    * program built against the SDK. The same program showed a pseudo-terminal
    * answering `ENOTTY` to it, so only a driver that implements it (a USB
    * serial adapter) takes a non-standard rate here.
    */
  val nonStandardRates: NonStandardRates =
    NonStandardRates.ThroughIoctl(0x80085402L)
}
