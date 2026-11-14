package serial4s.platform

import serial4s.errors.ErrnoName
import serial4s.settings.CharSize

/** Linux on x86_64 and aarch64, with **glibc 2.42 or later**: that release made
  * `speed_t` the rate itself, which is all this code assumes. An older glibc,
  * and musl, refuse the rate in `cfsetspeed`, and opening a port then fails
  * with `OpenError.InvalidSettings`.
  *
  * **Checked on x86_64 with glibc 2.42** (Ubuntu 25.10, in Docker): a C probe
  * printed every constant below and they match, and the whole test suite passes
  * (`docker/test-linux.sh`). aarch64 has not been run; the values are the same
  * generic kernel and glibc ones. They were first read from the sources:
  *   - glibc `sysdeps/unix/sysv/linux/bits/termios-struct.h`,
  *     `termios-c_cflag.h`, `termios-c_iflag.h`, `termios-c_cc.h`,
  *     `bits/termios.h` (`tcflag_t` and `speed_t` are `unsigned int`, `cc_t` is
  *     `unsigned char`), `bits/typesizes.h` (`__FD_SETSIZE` is 1024,
  *     `suseconds_t` is a `long`), `misc/sys/file.h` and `bits/fcntl-linux.h`;
  *   - the kernel's `include/uapi/asm-generic/errno-base.h`, `fcntl.h`,
  *     `termbits.h` and `termbits-common.h`, which x86_64 and aarch64 both use;
  *   - the kernel's `Documentation/admin-guide/devices.txt` for the device
  *     names.
  *
  * The `struct termios` offsets follow the glibc declaration: four 4-byte flag
  * words (0 to 15), `c_line` (16), `c_cc[32]` (17 to 48), then two 4-byte
  * speeds aligned at 52 and 56, 60 bytes in all.
  */
private[serial4s] object Linux extends Platform {

  val name: String = "Linux"

  val portDirectory: String = "/dev"

  private val SerialName = raw"(ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm)\d+".r

  /** `ttyS`, `ttyUSB`, `ttyACM`, `ttyAMA` and `rfcomm`, followed by a number,
    * as the kernel's `devices.txt` names them. `ttyS*` includes legacy UARTs
    * that have no hardware behind them.
    */
  def isSerialPortName(name: String): Boolean = SerialName.matches(name)

  val errnos: Map[ErrnoName, Int] = Map(
    ErrnoName.EPERM   -> 1,
    ErrnoName.ENOENT  -> 2,
    ErrnoName.EINTR   -> 4,
    ErrnoName.EIO     -> 5,
    ErrnoName.ENXIO   -> 6,
    ErrnoName.EBADF   -> 9,
    ErrnoName.EAGAIN  -> 11,
    ErrnoName.EACCES  -> 13,
    ErrnoName.EFAULT  -> 14,
    ErrnoName.EBUSY   -> 16,
    ErrnoName.ENODEV  -> 19,
    ErrnoName.ENOTDIR -> 20,
    ErrnoName.EINVAL  -> 22,
    ErrnoName.ENFILE  -> 23,
    ErrnoName.EMFILE  -> 24,
    ErrnoName.ENOTTY  -> 25,
    ErrnoName.EPIPE   -> 32
  )

  // asm-generic/fcntl.h: O_RDWR is 1 << 1, O_NOCTTY 1 << 8, O_NONBLOCK 1 << 11, O_CLOEXEC 1 << 19
  val oRdwr: Int     = 2
  val oNonblock: Int = 2048
  val oNoctty: Int   = 256
  val oCloexec: Int  = 524288
  val fGetfl: Int    = 3
  val fSetfl: Int    = 4
  val fSetfd: Int    = 2
  val fdCloexec: Int = 1
  val lockEx: Int    = 2
  val lockNb: Int    = 4

  // fd_set is 1024 bits; struct timeval is two 8-byte fields on a 64-bit target.
  val fdSetBytes: Int = 128
  // glibc 2.42's `select` takes a bitmap bigger than `fd_set` (a C probe showed a descriptor of
  // 1500 working), so a descriptor of 1024 or more needs no special symbol.
  val selectSymbol: String    = "select"
  val timevalSize: Int        = 16
  val timevalUsecOffset: Int  = 8
  val timevalUsecWidth: Width = Width.Bits64

  val termiosSize: Int = 60
  val flagWidth: Width = Width.Bits32
  val iflagOffset: Int = 0
  val cflagOffset: Int = 8
  val ccOffset: Int    = 17
  val vmin: Int        = 6
  val vtime: Int       = 5
  val tcsanow: Int     = 0
  val tcioflush: Int   = 2
  val csize: Long      = 0x30L
  val cstopb: Long     = 0x40L
  val cread: Long      = 0x80L
  val parenb: Long     = 0x100L
  val parodd: Long     = 0x200L
  val clocal: Long     = 0x800L
  val crtscts: Long    = 0x80000000L
  val ixon: Long       = 0x400L
  val ixoff: Long      = 0x1000L
  val ixany: Long      = 0x800L

  def charSizeBits(size: CharSize): Long = size match {
    case CharSize.Five  => 0x00L
    case CharSize.Six   => 0x10L
    case CharSize.Seven => 0x20L
    case CharSize.Eight => 0x30L
  }

  val speedWidth: Width = Width.Bits32

  /** glibc 2.42 and later take any rate in `cfsetspeed` and `cfgetospeed`:
    * `speed_t` is the rate itself, and an unlisted one becomes `BOTHER` inside
    * the library (NEWS for 2.42, and `sysdeps/unix/sysv/linux/speed.c`,
    * `tcsetattr.c`).
    */
  val nonStandardRates: NonStandardRates = NonStandardRates.ThroughTermios
}
