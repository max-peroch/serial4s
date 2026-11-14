package serial4s.platform

import serial4s.errors.ErrnoName
import serial4s.settings.CharSize
import munit.FunSuite

final class PlatformSuite extends FunSuite {

  private val platforms: List[Platform] = List(MacOs, Linux)

  private def named(platform: Platform)(check: Platform => Unit): Unit =
    try check(platform)
    catch {
      case failure: munit.FailException =>
        fail(s"${platform.name}: ${failure.getMessage}")
    }

  test("the operating system and architecture choose the platform") {
    assertEquals(Platform.forSystem("Mac OS X", "x86_64"), Right(MacOs))
    assertEquals(Platform.forSystem("Mac OS X", "aarch64"), Right(MacOs))
    List("amd64", "x86_64", "aarch64").foreach(architecture =>
      assertEquals(
        Platform.forSystem("Linux", architecture),
        Right(Linux),
        architecture
      )
    )
  }

  test("an unsupported system is refused, and the reason names it") {
    assertEquals(
      Platform.forSystem("Linux", "riscv64"),
      Left(NativeUnavailable("unsupported Linux architecture: riscv64"))
    )
    assertEquals(
      Platform.forSystem("Windows 11", "amd64"),
      Left(
        NativeUnavailable(
          "Windows has no POSIX platform: it needs its own backend"
        )
      )
    )
    assertEquals(
      Platform.forSystem("FreeBSD", "amd64"),
      Left(NativeUnavailable("unsupported operating system: FreeBSD"))
    )
  }

  test("every error this library tells apart has a number on every platform") {
    platforms.foreach(platform =>
      named(platform) { p =>
        val missing = ErrnoName.values
          .filter(_ != ErrnoName.Unknown)
          .filterNot(p.errnos.contains)
        assertEquals(missing.toList, Nil)
        assertEquals(
          p.errnos.values.toSet.size,
          p.errnos.size,
          "two errors share a number"
        )
      }
    )
  }

  test(
    "the same error has a different number on the two platforms: match on the name"
  ) {
    assertNotEquals(MacOs.code(ErrnoName.EAGAIN), Linux.code(ErrnoName.EAGAIN))
    assertEquals(
      MacOs.errnoName(MacOs.code(ErrnoName.EAGAIN)),
      ErrnoName.EAGAIN
    )
    assertEquals(
      Linux.errnoName(Linux.code(ErrnoName.EAGAIN)),
      ErrnoName.EAGAIN
    )
    assertEquals(Linux.errnoName(99999), ErrnoName.Unknown)
  }

  test(
    "the control flags are separate bits, and the character sizes fit in their mask"
  ) {
    platforms.foreach(platform =>
      named(platform) { p =>
        val separate =
          List(p.cstopb, p.cread, p.parenb, p.parodd, p.clocal, p.csize)
        for {
          (a, i) <- separate.zipWithIndex
          (b, j) <- separate.zipWithIndex
          if i < j
        } assertEquals(a & b, 0L, s"$a and $b overlap")
        val sizes = CharSize.values.toList.map(p.charSizeBits)
        assertEquals(sizes.distinct, sizes)
        assert(
          sizes.forall(size => (size & ~p.csize) == 0L),
          "a size has a bit outside the mask"
        )
        assertEquals(
          p.charSizeBits(CharSize.Eight),
          p.csize,
          "8 data bits fill the mask"
        )
        assertEquals(p.charSizeBits(CharSize.Five), 0L)
      }
    )
  }

  test(
    "the software flow control bits are separate, and hardware flow control is one flag word"
  ) {
    platforms.foreach(platform =>
      named(platform) { p =>
        assertEquals(p.ixon & p.ixoff, 0L)
        assertEquals(p.ixon & p.ixany, 0L)
        assertEquals(p.ixoff & p.ixany, 0L)
        assertEquals(
          p.crtscts & (p.cstopb | p.cread | p.parenb | p.parodd | p.clocal | p.csize),
          0L
        )
        val limit = p.flagWidth match {
          case Width.Bits32 => 0xffffffffL
          case Width.Bits64 => Long.MaxValue
        }
        assert(
          List(p.crtscts, p.csize, p.clocal, p.ixoff).forall(_ <= limit),
          "a flag does not fit"
        )
      }
    )
  }

  test("the termios offsets are inside the struct and in order") {
    platforms.foreach(platform =>
      named(platform) { p =>
        val flagBytes = p.flagWidth match {
          case Width.Bits32 => 4
          case Width.Bits64 => 8
        }
        assertEquals(p.iflagOffset, 0)
        assertEquals(
          p.cflagOffset,
          2 * flagBytes,
          "c_cflag is the third flag word"
        )
        assert(
          p.ccOffset >= 4 * flagBytes,
          "c_cc starts after the four flag words"
        )
        assert(
          p.ccOffset + math.max(p.vmin, p.vtime) < p.termiosSize,
          "VMIN or VTIME is outside"
        )
        assertNotEquals(p.vmin, p.vtime)
      }
    )
  }

  test(
    "macOS sets a non-standard rate by ioctl: the request is _IOW('T', 2, speed_t)"
  ) {
    // _IOW: the "in" direction bit 0x80000000, the size of speed_t (8 bytes) at bit 16,
    // the group 'T' at bit 8 and the number 2
    val request = 0x80000000L | (8L << 16) | ('T'.toLong << 8) | 2L
    assertEquals(request, 0x80085402L)
    assertEquals(MacOs.nonStandardRates, NonStandardRates.ThroughIoctl(request))
  }

  test("Linux sets a non-standard rate through termios, as glibc 2.42 allows") {
    assertEquals(Linux.nonStandardRates, NonStandardRates.ThroughTermios)
  }

  test("speed_t is the width the platform's C library uses") {
    assertEquals(MacOs.speedWidth, Width.Bits64)
    assertEquals(Linux.speedWidth, Width.Bits32)
  }

  test(
    "a Linux serial device is ttyS, ttyUSB, ttyACM, ttyAMA or rfcomm followed by a number"
  ) {
    val serial = List(
      "ttyS0",
      "ttyS31",
      "ttyUSB0",
      "ttyUSB12",
      "ttyACM0",
      "ttyAMA0",
      "rfcomm3"
    )
    val other = List(
      "tty",
      "tty0",
      "ttyUSB",
      "ttyUSB0x",
      "ttyUSBa",
      "console",
      "null",
      "pts",
      "cu.usbmodem1",
      "serial",
      "rfcomm"
    )
    assertEquals(
      serial.map(Linux.isSerialPortName),
      List.fill(serial.size)(true)
    )
    assertEquals(
      other.map(Linux.isSerialPortName),
      List.fill(other.size)(false)
    )
  }
}
