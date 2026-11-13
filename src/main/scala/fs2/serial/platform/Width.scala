package fs2.serial.platform

/** The size of an integer field in a C struct: the two sizes the supported
  * platforms use.
  */
private[serial] enum Width {
  case Bits32, Bits64
}
