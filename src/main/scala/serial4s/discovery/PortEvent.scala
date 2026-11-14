package serial4s.discovery

/** A change in the serial ports of the machine, as seen by [[Serial.watch]]. */
enum PortEvent {

  /** A port that was not there before. `path` can be given to [[Serial.open]].
    */
  case Appeared(path: String)

  /** A port that was there before and is gone: unplugged, or its device was
    * removed.
    */
  case Disappeared(path: String)
}
