package fs2.serial.testing

import cats.effect.IO
import fs2.serial.errors.Errno
import fs2.serial.posix.{Fd, Posix, ReadOutcome}

/** Byte-array helpers over [[Posix]]: native calls need native memory, so each
  * goes through a buffer.
  */
private[serial] object Bytes {

  def write(
      posix: Posix[IO],
      fd: Fd,
      bytes: Array[Byte]
  ): IO[Either[Errno, Int]] =
    posix
      .buffer(math.max(bytes.length, 1))
      .use(buffer =>
        buffer.fill(bytes, 0, bytes.length) *> buffer.writeTo(fd, bytes.length)
      )

  /** What was read; empty at end of file. */
  def read(posix: Posix[IO], fd: Fd, max: Int): IO[Either[Errno, Array[Byte]]] =
    posix
      .buffer(max)
      .use(buffer =>
        buffer.readFrom(fd, max).flatMap {
          case Right(ReadOutcome.Data(count)) =>
            buffer.bytes(count).map(Right(_))
          case Right(ReadOutcome.EndOfFile) =>
            IO.pure(Right(Array.emptyByteArray))
          case Left(errno) => IO.pure(Left(errno))
        }
      )
}
