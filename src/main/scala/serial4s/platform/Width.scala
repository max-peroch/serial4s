package serial4s.platform

/** The size of an integer field in a C struct: the two sizes the supported
  * platforms use.
  */
private[serial4s] enum Width {
  case Bits32, Bits64
}
