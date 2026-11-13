package fs2.serial.win32

import fs2.serial.errors.WindowsError

/** One overlapped transfer: a block of native memory, the `OVERLAPPED` that
  * tracks it, and the event it signals. It is used for one transfer at a time,
  * and nothing may free it while a transfer runs, because the driver writes
  * into it. Every `count` is bounded by [[size]].
  */
private[serial] trait Operation[F[_]] {

  def size: Int

  /** Copies `count` bytes of `bytes`, from `from`, to the start of the block.
    */
  def fill(bytes: Array[Byte], from: Int, count: Int): F[Unit]

  /** The first `count` bytes of the block, copied out. */
  def bytes(count: Int): F[Array[Byte]]

  /** Begins reading up to `count` bytes into the block. */
  def startRead(handle: Handle, count: Int): F[Either[WindowsError, Started]]

  /** Begins writing the first `count` bytes of the block. */
  def startWrite(handle: Handle, count: Int): F[Either[WindowsError, Started]]

  /** Waits until the transfer finishes or `wake` is signalled. If both are, the
    * transfer wins. It blocks, so it runs where blocking is allowed.
    */
  def await(wake: Event): F[Either[WindowsError, Awaited]]

  /** The bytes a finished transfer moved. */
  def complete(handle: Handle): F[Either[WindowsError, Int]]

  /** Cancels the transfer, waits until it has stopped, and says how many bytes
    * it had moved. A cancelled read can still have taken bytes: they are in the
    * block and the caller must keep them. After this the block is free again.
    */
  def cancel(handle: Handle): F[Either[WindowsError, Int]]
}
