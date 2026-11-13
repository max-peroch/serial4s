package fs2.serial.win32

/** What `QueryDosDeviceW` returns, and the COM ports in it. */
private[serial] object DosDevices {

  /** The names in a double-NUL-terminated list: `COM1\0COM3\0\0`. */
  def parse(text: String): List[String] =
    text.split('\u0000').toList.filter(_.nonEmpty)

  private val Com = raw"COM(\d+)".r

  /** The COM ports among `names`, in numeric order (`COM9` before `COM10`). */
  def comPorts(names: List[String]): List[String] =
    names
      .collect { case name @ Com(number) => (number.toLongOption, name) }
      .collect { case (Some(number), name) => (number, name) }
      .sortBy(_._1)
      .map(_._2)
      .distinct
}
