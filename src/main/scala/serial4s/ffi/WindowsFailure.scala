package serial4s.ffi

import serial4s.errors.WindowsError

import scala.util.control.NoStackTrace

/** A Windows call that cannot fail in a way anyone can act on, and did (setting
  * an event that exists, for one). It fails the effect: it is a defect, not a
  * value the caller handles.
  */
private[serial4s] final case class WindowsFailure(error: WindowsError)
    extends RuntimeException(s"Windows call failed: $error")
    with NoStackTrace
