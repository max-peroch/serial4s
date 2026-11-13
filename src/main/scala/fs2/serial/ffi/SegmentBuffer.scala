package fs2.serial.ffi

import cats.effect.kernel.Sync
import fs2.serial.errors.Errno
import fs2.serial.posix.{Fd, IoBuffer, ReadOutcome}

import java.lang.foreign.{MemorySegment, ValueLayout}

/** An [[IoBuffer]] over a native memory segment. The segment must be shared:
  * calls run on whichever thread the effect lands on.
  *
  * Every descriptor this library reads or writes is non-blocking, so `readFrom`
  * and `writeTo` return at once and run with `delay`, with no hop to the
  * blocking pool: the only call that waits is `select`.
  */
private[ffi] final class SegmentBuffer[F[_]](
    calls: Syscalls,
    segment: MemorySegment
)(using
    F: Sync[F]
) extends IoBuffer[F] {

  val size: Int = segment.byteSize().toInt

  def fill(bytes: Array[Byte], from: Int, count: Int): F[Unit] =
    F.delay {
      val offset = Math.max(0, Math.min(from, bytes.length))
      val taken  =
        Math.max(0, Math.min(count, Math.min(size, bytes.length - offset)))
      MemorySegment.copy(
        bytes,
        offset,
        segment,
        ValueLayout.JAVA_BYTE,
        0L,
        taken
      )
    }

  def bytes(count: Int): F[Array[Byte]] =
    F.delay {
      val taken = Math.max(0, Math.min(count, size))
      val out   = new Array[Byte](taken)
      MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0L, out, 0, taken)
      out
    }

  def readFrom(fd: Fd, count: Int): F[Either[Errno, ReadOutcome]] =
    F.delay(calls.read(fd, segment, count))

  def writeTo(fd: Fd, count: Int): F[Either[Errno, Int]] =
    F.delay(calls.write(fd, segment, count))
}
