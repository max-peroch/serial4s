package fs2.serial.settings

/** How the two ends of the line agree on when to send. */
enum FlowControl {

  /** None: the sender never waits. Bytes are lost if the receiver cannot keep
    * up.
    */
  case Off

  /** RTS/CTS, on the control wires. */
  case Hardware

  /** XON/XOFF, in the data stream: the two bytes `0x11` and `0x13` become
    * reserved.
    */
  case Software
}
