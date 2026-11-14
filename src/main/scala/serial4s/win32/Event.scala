package serial4s.win32

/** A Windows event object: what a wait watches, and what wakes it. Kept apart
  * from [[Handle]] so a device handle is never passed where an event is meant.
  */
private[serial4s] opaque type Event = Long

private[serial4s] object Event {

  def apply(value: Long): Event = value

  extension (event: Event) {
    def value: Long = event
  }
}
