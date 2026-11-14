package serial4s.settings

/** Line settings for a port. The default is 115200 baud, 8 data bits, no
  * parity, one stop bit and no flow control (`115200 8N1`).
  */
final case class SerialSettings(
    baudRate: BaudRate = BaudRate.B115200,
    charSize: CharSize = CharSize.Eight,
    parity: Parity = Parity.NoParity,
    stopBits: StopBits = StopBits.One,
    flowControl: FlowControl = FlowControl.Off
)
