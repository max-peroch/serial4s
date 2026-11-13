package fs2.serial.port

import fs2.serial.errors.{ReadError, WriteError}
import fs2.serial.settings.SerialSettings
import fs2.{Chunk, Pipe, Stream}

/** An open serial port, shaped like fs2-io's `Socket`: `read` and `write` are
  * the effects, `reads` and `writes` the stream and the pipe built on them.
  *
  * It is valid until the `Resource` it came from is released; after that every
  * operation reports `Closed`. One read at a time and one write at a time: a
  * second caller waits for the first. A read and a write can run together.
  */
trait SerialPort[F[_]] {

  /** The path the port was opened at. */
  def path: String

  /** The line settings it was opened with. */
  def settings: SerialSettings

  /** Waits for bytes and returns what is available, at most one buffer's worth.
    * Cancelling it is safe and prompt, and loses no byte.
    */
  def read: F[Either[ReadError, Chunk[Byte]]]

  /** Writes every byte, in order, waiting when the line is full. Writes never
    * interleave.
    */
  def write(chunk: Chunk[Byte]): F[Either[WriteError, Unit]]

  /** The bytes that arrive, as a stream that reads only when it is pulled. A
    * serial port has no end of input: the stream fails with
    * `SerialException.Read` when the device goes away or the port is closed.
    */
  def reads: Stream[F, Byte]

  /** Writes the bytes of a stream. Fails with `SerialException.Write`. */
  def writes: Pipe[F, Byte, Nothing]

  /** Writes what comes in and emits what arrives, at the same time: the shape
    * of a request and response over a serial line. Ends when the port fails; a
    * failing write fails it too.
    */
  def duplex: Pipe[F, Byte, Byte]
}
