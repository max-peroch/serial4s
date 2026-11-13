package fs2.serial.ffi

import cats.effect.kernel.Sync
import fs2.serial.errors.WindowsError
import fs2.serial.win32.{Awaited, Event, Handle, Operation, Started}

/** An [[Operation]] over a [[KernelOperation]]. Starting and finishing a
  * transfer return at once, so they run with `delay`; waiting and cancelling
  * block, so they run where blocking is allowed.
  */
private[ffi] final class WindowsOperation[F[_]](operation: KernelOperation)(
    using F: Sync[F]
) extends Operation[F] {

  val size: Int = operation.size

  def fill(bytes: Array[Byte], from: Int, count: Int): F[Unit] =
    F.delay(operation.fill(bytes, from, count))

  def bytes(count: Int): F[Array[Byte]] = F.delay(operation.bytes(count))

  def startRead(handle: Handle, count: Int): F[Either[WindowsError, Started]] =
    F.delay(operation.startRead(handle, count))

  def startWrite(handle: Handle, count: Int): F[Either[WindowsError, Started]] =
    F.delay(operation.startWrite(handle, count))

  def await(wake: Event): F[Either[WindowsError, Awaited]] =
    F.blocking(operation.await(wake))

  def complete(handle: Handle): F[Either[WindowsError, Int]] =
    F.delay(operation.complete(handle))

  def cancel(handle: Handle): F[Either[WindowsError, Int]] =
    F.blocking(operation.cancel(handle))
}
