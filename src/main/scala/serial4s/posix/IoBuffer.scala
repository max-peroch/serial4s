package serial4s.posix

import serial4s.errors.Errno

/** A fixed-size block of native memory that system calls read into and write
  * from.
  *
  * It comes from [[Posix.buffer]] and is valid until that `Resource` is
  * released. It keeps the memory out of everything above `ffi`: callers copy
  * bytes in and out, and never see a pointer. `fill` and `bytes` clamp every
  * `count` to what fits; `readFrom` and `writeTo` refuse a `count` of zero or
  * less with `EINVAL`, because a zero-length read returns 0, which would look
  * like end of file.
  */
private[serial4s] trait IoBuffer[F[_]] {

  def size: Int

  /** Copies `count` bytes of `bytes`, starting at `from`, to the start of the
    * buffer.
    */
  def fill(bytes: Array[Byte], from: Int, count: Int): F[Unit]

  /** The first `count` bytes of the buffer, copied out. */
  def bytes(count: Int): F[Array[Byte]]

  /** `read(2)` from `fd` into the buffer: at most `count` bytes. */
  def readFrom(fd: Fd, count: Int): F[Either[Errno, ReadOutcome]]

  /** `write(2)` the first `count` bytes of the buffer to `fd`. May write fewer.
    */
  def writeTo(fd: Fd, count: Int): F[Either[Errno, Int]]
}
