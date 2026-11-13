package fs2.serial.win32

/** A Windows event object: what a wait watches, and what wakes it. Kept apart
  * from [[Handle]] so a device handle is never passed where an event is meant.
  */
private[serial] opaque type Event = Long

private[serial] object Event {

  def apply(value: Long): Event = value

  extension (event: Event) {
    def value: Long = event
  }
}
