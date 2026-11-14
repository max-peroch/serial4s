package serial4s.posix

/** What a successful `read(2)` produced. */
private[serial4s] enum ReadOutcome {

  /** `count` bytes, at least one, now in the buffer. */
  case Data(count: Int)

  /** Nothing more will ever come: the device is gone or the other end closed.
    */
  case EndOfFile
}
