package fs2.serial.win32

import fs2.serial.errors.{WindowsError, WindowsErrorName}
import fs2.serial.settings.*
import munit.FunSuite

final class DcbSuite extends FunSuite {

  private def rate(value: Int): BaudRate =
    BaudRate(value).fold(error => fail(s"bad rate: $error"), identity)

  /** A DCB as a driver might report it: unrelated bits set, to see they
    * survive.
    */
  private val driver = DcbFields(
    baudRate = 1200,
    flags =
      0xfff88000 | (1 << 7) | (1 << 14), // fDummy2 bits, and two flags to clear
    byteSize = 7,
    parity = 3,
    stopBits = 1,
    xonChar = 0,
    xoffChar = 0
  )

  private val allSettings: List[SerialSettings] = for {
    baud    <- List(50, 9600, 115200, 250000, 4000000).map(rate)
    size    <- CharSize.values.toList
    parity  <- Parity.values.toList
    stop    <- StopBits.values.toList
    control <- FlowControl.values.toList
  } yield SerialSettings(baud, size, parity, stop, control)

  private def bit(flags: Int, index: Int): Boolean = ((flags >> index) & 1) == 1
  private def field(flags: Int, shift: Int): Int   = (flags >> shift) & 3

  test(
    "the layout is the 28-byte DCB of winbase.h, with every offset inside it"
  ) {
    assertEquals(Dcb.Size, 28)
    val offsets = List(
      Dcb.LengthOffset,
      Dcb.BaudRateOffset,
      Dcb.FlagsOffset,
      Dcb.ByteSizeOffset,
      Dcb.ParityOffset,
      Dcb.StopBitsOffset,
      Dcb.XonCharOffset,
      Dcb.XoffCharOffset
    )
    assertEquals(offsets, offsets.sorted)
    assertEquals(offsets.distinct, offsets)
    assert(offsets.forall(_ < Dcb.Size))
    assertEquals(Dcb.FlagsOffset, 8) // after two DWORDs
    assertEquals(
      Dcb.ByteSizeOffset,
      18
    ) // after the flags, wReserved, XonLim, XoffLim
  }

  test("the flags this library owns are exactly bits 0 to 14") {
    assertEquals(Dcb.Owned, 0x7fff)
  }

  test(
    "settings are laid over the driver's DCB: its other bits and fields are kept"
  ) {
    allSettings.foreach { settings =>
      val merged = Dcb.merge(settings, driver)
      assertEquals(
        merged.flags & ~Dcb.Owned,
        driver.flags & ~Dcb.Owned,
        settings.toString
      )
    }
  }

  test(
    "binary mode and DTR are always on, and DSR flow control and abort-on-error always off"
  ) {
    allSettings.foreach { settings =>
      val flags = Dcb.merge(settings, driver).flags
      assert(bit(flags, 0), s"fBinary: $settings")
      assertEquals(
        field(flags, 4),
        1,
        s"fDtrControl: $settings"
      ) // DTR_CONTROL_ENABLE
      assert(!bit(flags, 3), s"fOutxDsrFlow: $settings")
      assert(!bit(flags, 6), s"fDsrSensitivity: $settings")
      assert(!bit(flags, 7), s"fTXContinueOnXoff: $settings")
      assert(!bit(flags, 10), s"fErrorChar: $settings")
      assert(!bit(flags, 11), s"fNull: $settings")
      assert(!bit(flags, 14), s"fAbortOnError: $settings")
    }
  }

  test(
    "hardware flow control is CTS output flow and RTS handshake, and nothing in the data stream"
  ) {
    val flags = Dcb
      .merge(SerialSettings(flowControl = FlowControl.Hardware), driver)
      .flags
    assert(bit(flags, 2))
    assertEquals(field(flags, 12), 2) // RTS_CONTROL_HANDSHAKE
    assert(!bit(flags, 8) && !bit(flags, 9))
  }

  test("software flow control is XON/XOFF both ways, with RTS plainly on") {
    val merged =
      Dcb.merge(SerialSettings(flowControl = FlowControl.Software), driver)
    assert(bit(merged.flags, 8) && bit(merged.flags, 9))
    assert(!bit(merged.flags, 2))
    assertEquals(field(merged.flags, 12), 1) // RTS_CONTROL_ENABLE
    assertEquals((merged.xonChar, merged.xoffChar), (0x11, 0x13))
  }

  test("no flow control is nothing but RTS on") {
    val flags =
      Dcb.merge(SerialSettings(flowControl = FlowControl.Off), driver).flags
    assert(!bit(flags, 2) && !bit(flags, 8) && !bit(flags, 9))
    assertEquals(field(flags, 12), 1)
  }

  test("parity checking is on exactly when there is a parity") {
    Parity.values.foreach { parity =>
      val flags = Dcb.merge(SerialSettings(parity = parity), driver).flags
      assertEquals(bit(flags, 1), parity != Parity.NoParity, parity.toString)
    }
  }

  test("size, parity and stop bits use the codes of winbase.h") {
    val merged = Dcb.merge(
      SerialSettings(rate(57600), CharSize.Seven, Parity.Odd, StopBits.Two),
      driver
    )
    assertEquals(
      (merged.baudRate, merged.byteSize, merged.parity, merged.stopBits),
      (57600, 7, 1, 2) // ODDPARITY is 1, TWOSTOPBITS is 2
    )
    val even = Dcb.merge(
      SerialSettings(parity = Parity.Even, stopBits = StopBits.One),
      driver
    )
    assertEquals((even.parity, even.stopBits), (2, 0)) // EVENPARITY, ONESTOPBIT
  }

  test("every setting survives merge and decode") {
    allSettings.foreach { settings =>
      assertEquals(
        Dcb.decode(Dcb.merge(settings, driver)),
        Right(settings),
        settings.toString
      )
    }
  }

  test(
    "a DCB this library cannot represent is refused as an invalid parameter"
  ) {
    val good    = Dcb.merge(SerialSettings(), driver)
    val invalid =
      Left(WindowsError(87, WindowsErrorName.ERROR_INVALID_PARAMETER))
    val refused = List(
      good.copy(parity = 3),   // MARKPARITY
      good.copy(parity = 4),   // SPACEPARITY
      good.copy(stopBits = 1), // ONE5STOPBITS
      good.copy(byteSize = 4),
      good.copy(byteSize = 9),
      good.copy(baudRate = 0),
      good.copy(baudRate = Int.MaxValue)
    )
    refused.foreach(fields =>
      assertEquals(Dcb.decode(fields), invalid, fields.toString)
    )
  }
}
