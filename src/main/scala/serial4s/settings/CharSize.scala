package serial4s.settings

/** Data bits per character (`CS5` to `CS8`). */
enum CharSize(val bits: Int) {
  case Five  extends CharSize(5)
  case Six   extends CharSize(6)
  case Seven extends CharSize(7)
  case Eight extends CharSize(8)
}
