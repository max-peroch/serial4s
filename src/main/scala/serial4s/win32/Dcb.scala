package serial4s.win32

import serial4s.errors.{WindowsError, WindowsErrorName}
import serial4s.settings.{
  BaudRate,
  CharSize,
  FlowControl,
  Parity,
  SerialSettings,
  StopBits
}

/** The fields of a `DCB` this library sets, as `GetCommState` returned them.
  * The rest of the struct is left as the driver had it.
  */
private[serial4s] final case class DcbFields(
    baudRate: Int,
    flags: Int,
    byteSize: Int,
    parity: Int,
    stopBits: Int,
    xonChar: Int,
    xoffChar: Int
)

/** The line settings of a serial port as a Windows `DCB`, and back.
  *
  * The layout is `struct _DCB` in mingw-w64's `winbase.h`: 28 bytes, with the
  * flags as a bitfield of `DWORD` size filled from the low bit up, in the order
  * the fields are declared. That bit order is the one compiler convention
  * Microsoft's own uses; the first run on Windows reads settings back and
  * checks it.
  */
private[serial4s] object Dcb {

  // byte offsets in the struct (x64 and ARM64: the same)
  val Size: Int           = 28
  val LengthOffset: Int   = 0
  val BaudRateOffset: Int = 4
  val FlagsOffset: Int    = 8
  val ByteSizeOffset: Int = 18
  val ParityOffset: Int   = 19
  val StopBitsOffset: Int = 20
  val XonCharOffset: Int  = 21
  val XoffCharOffset: Int = 22

  // the flag bits, from the declaration order of the bitfield
  private val Binary: Int           = 1 << 0
  private val CheckParity: Int      = 1 << 1
  private val OutxCtsFlow: Int      = 1 << 2
  private val OutxDsrFlow: Int      = 1 << 3
  private val DtrControlShift: Int  = 4
  private val DsrSensitivity: Int   = 1 << 6
  private val TxContinueOnXoff: Int = 1 << 7
  private val OutX: Int             = 1 << 8
  private val InX: Int              = 1 << 9
  private val ErrorChar: Int        = 1 << 10
  private val Null: Int             = 1 << 11
  private val RtsControlShift: Int  = 12
  private val AbortOnError: Int     = 1 << 14

  /** Bits 0 to 14: every named flag, each of which this library sets (the ones
    * it wants off, such as DSR flow control and abort-on-error, to zero). The
    * rest (`fDummy2`) is left alone.
    */
  val Owned: Int =
    Binary | CheckParity | OutxCtsFlow | OutxDsrFlow | (3 << DtrControlShift) |
      DsrSensitivity | TxContinueOnXoff | OutX | InX | ErrorChar | Null |
      (3 << RtsControlShift) | AbortOnError

  private val DtrEnable: Int    = 1 // DTR_CONTROL_ENABLE
  private val RtsEnable: Int    = 1 // RTS_CONTROL_ENABLE
  private val RtsHandshake: Int = 2 // RTS_CONTROL_HANDSHAKE

  private val NoParity: Int   = 0
  private val OddParity: Int  = 1
  private val EvenParity: Int = 2
  private val OneStop: Int    = 0
  private val TwoStops: Int   = 2

  val XonChar: Int  = 0x11
  val XoffChar: Int = 0x13

  private def flagsFor(settings: SerialSettings): Int = {
    val hardware = settings.flowControl == FlowControl.Hardware
    val software = settings.flowControl == FlowControl.Software
    def bit(on: Boolean, mask: Int): Int = if (on) mask else 0
    Binary |
      bit(settings.parity != Parity.NoParity, CheckParity) |
      bit(hardware, OutxCtsFlow) |
      (DtrEnable << DtrControlShift) |
      bit(software, OutX | InX) |
      ((if (hardware) RtsHandshake else RtsEnable) << RtsControlShift)
  }

  /** The fields to set: the settings laid over what the driver reported. The
    * flags this library does not own are kept; the ones it does are all set
    * explicitly, so none is left to whatever the driver started with.
    */
  def merge(settings: SerialSettings, current: DcbFields): DcbFields =
    current.copy(
      baudRate = settings.baudRate.value,
      flags = (current.flags & ~Owned) | flagsFor(settings),
      byteSize = settings.charSize.bits,
      parity = settings.parity match {
        case Parity.NoParity => NoParity
        case Parity.Odd      => OddParity
        case Parity.Even     => EvenParity
      },
      stopBits = settings.stopBits match {
        case StopBits.One => OneStop
        case StopBits.Two => TwoStops
      },
      xonChar = XonChar,
      xoffChar = XoffChar
    )

  private def invalid: WindowsError =
    WindowsErrors.of(WindowsErrorName.ERROR_INVALID_PARAMETER)

  /** The settings a `DCB` describes, or `ERROR_INVALID_PARAMETER` if it is set
    * to something this library cannot represent (mark or space parity, 1.5 stop
    * bits, a rate out of range).
    */
  def decode(fields: DcbFields): Either[WindowsError, SerialSettings] = {
    val rts = (fields.flags >> RtsControlShift) & 3
    for {
      baud   <- BaudRate(fields.baudRate).left.map(_ => invalid)
      size   <- CharSize.values.find(_.bits == fields.byteSize).toRight(invalid)
      parity <- fields.parity match {
        case NoParity   => Right(Parity.NoParity)
        case OddParity  => Right(Parity.Odd)
        case EvenParity => Right(Parity.Even)
        case _          => Left(invalid)
      }
      stop <- fields.stopBits match {
        case OneStop  => Right(StopBits.One)
        case TwoStops => Right(StopBits.Two)
        case _        => Left(invalid)
      }
    } yield SerialSettings(
      baudRate = baud,
      charSize = size,
      parity = parity,
      stopBits = stop,
      flowControl =
        if ((fields.flags & OutxCtsFlow) != 0 && rts == RtsHandshake)
          FlowControl.Hardware
        else if ((fields.flags & (OutX | InX)) != 0) FlowControl.Software
        else FlowControl.Off
    )
  }
}
