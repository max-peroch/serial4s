package fs2.serial.errors

import scala.util.control.NoStackTrace

/** What a stream fails with: a stream's error channel is a `Throwable`, so the
  * scoped error travels inside one. Use `read` and `write` to get the error as
  * a value instead.
  */
sealed abstract class SerialException(message: String)
    extends Exception(message)
    with NoStackTrace

object SerialException {

  final case class Read(error: ReadError)
      extends SerialException(s"serial read failed: $error")

  final case class Write(error: WriteError)
      extends SerialException(s"serial write failed: $error")

  final case class Listing(error: ListError)
      extends SerialException(s"serial port listing failed: $error")

  final case class Open(error: OpenError)
      extends SerialException(s"opening a serial port failed: $error")
}
