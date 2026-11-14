package serial4s.backend

import fs2.Chunk
import serial4s.errors.{ReadError, WriteError}

/** A serial device that is open and configured: the one thing every operating
  * system's backend has to provide, whatever it calls it underneath (a file
  * descriptor, a Windows handle).
  *
  * It does not serialise callers: the port above it runs one read and one write
  * at a time. What it does promise is how it waits. A `read` or `write` that is
  * cancelled returns promptly, loses no byte, and leaves nothing running that
  * still uses the device; and [[shutdown]] makes every pending and later call
  * return `Closed`.
  */
private[serial4s] trait Device[F[_]] {

  /** Waits for bytes and returns what is available, at most one buffer's worth.
    */
  def read: F[Either[ReadError, Chunk[Byte]]]

  /** Writes every byte, in order, waiting while the line is full. */
  def write(chunk: Chunk[Byte]): F[Either[WriteError, Unit]]

  /** Asks every pending and every later `read` and `write` to finish with
    * `Closed`. It does not wait for them: the caller does that, then releases
    * the device.
    */
  def shutdown: F[Unit]
}
