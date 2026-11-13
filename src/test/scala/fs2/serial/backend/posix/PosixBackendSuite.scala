package fs2.serial.backend.posix

import fs2.serial.errors.{OpenError, ReadError, SerialException, WriteError}
import fs2.serial.port.{ManagedPort, SerialPort}
import fs2.serial.posix.{Selection, WaitLimit}
import fs2.serial.settings.SerialSettings
import fs2.serial.testing.{Bytes, PosixFixture, PseudoTerminal, SelectSpy}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import java.util.concurrent.TimeoutException
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** The public API over a real pseudo-terminal pair: no hardware, real system
  * calls.
  */
final class PosixBackendSuite extends CatsEffectSuite with PosixFixture {

  private def ptyTest(name: String)(body: PseudoTerminal.Pty => IO[Any]): Unit =
    test(name)(PseudoTerminal.resource(posix).use(body))

  private def openPort(
      path: String,
      via: fs2.serial.posix.Posix[IO] = posix
  ): Resource[IO, Either[OpenError, SerialPort[IO]]] =
    ManagedPort
      .open[IO](new PosixBackend[IO](via, platform), path, SerialSettings())

  private def withPort[A](path: String)(use: SerialPort[IO] => IO[A]): IO[A] =
    openPort(path).use {
      case Right(port) => use(port)
      case Left(error) =>
        IO.raiseError(new AssertionError(s"open failed: $error"))
    }

  private def toMaster(pty: PseudoTerminal.Pty, bytes: Array[Byte]): IO[Unit] =
    Bytes.write(posix, pty.master, bytes).flatMap {
      case Right(count) if count == bytes.length => IO.unit
      case other => IO.raiseError(new AssertionError(s"master write: $other"))
    }

  /** Reads exactly `count` bytes from the master, failing instead of hanging if
    * they never come.
    */
  private def fromMaster(
      pty: PseudoTerminal.Pty,
      count: Int
  ): IO[Vector[Byte]] = {
    def go(have: Vector[Byte]): IO[Vector[Byte]] =
      if (have.size >= count) IO.pure(have)
      else
        posix.select(List(pty.master), Nil, WaitLimit.UpTo(5.seconds)).flatMap {
          case Right(Selection(List(_), _)) =>
            Bytes.read(posix, pty.master, count - have.size).flatMap {
              case Right(bytes) if bytes.nonEmpty => go(have ++ bytes)
              case other                          =>
                IO.raiseError(new AssertionError(s"master read: $other"))
            }
          case other =>
            IO.raiseError(new AssertionError(s"master got nothing: $other"))
        }
    go(Vector.empty)
  }

  ptyTest("read returns the bytes the master wrote") { pty =>
    withPort(pty.slavePath) { port =>
      toMaster(pty, "hello".getBytes) >> port.read.map(
        _.map(_.toArray.toList)
      )
    }.assertEquals(Right("hello".getBytes.toList))
  }

  ptyTest("the read stream is pull-based and can be taken from") { pty =>
    withPort(pty.slavePath) { port =>
      toMaster(pty, "abcde".getBytes) >> port.reads.take(5).compile.toList
    }.assertEquals("abcde".getBytes.toList)
  }

  ptyTest("the write pipe delivers its bytes to the master") { pty =>
    withPort(pty.slavePath) { port =>
      Stream
        .emits("abc".getBytes)
        .covary[IO]
        .through(port.writes)
        .compile
        .drain >>
        fromMaster(pty, 3)
    }.assertEquals("abc".getBytes.toVector)
  }

  ptyTest("a write bigger than the line's buffer arrives whole and in order") {
    pty =>
      val payload = Array.tabulate[Byte](200000)(i => (i % 251).toByte)
      withPort(pty.slavePath) { port =>
        (
          port.write(Chunk.array(payload)),
          fromMaster(pty, payload.length)
        ).parTupled
      }.map { case (written, received) =>
        (written, received == payload.toVector)
      }.timeout(30.seconds)
        .assertEquals((Right(()), true))
  }

  ptyTest(
    "cancelling a read is prompt and the next read gets the bytes, not a stale wake"
  ) { pty =>
    withPort(pty.slavePath) { port =>
      for {
        cancelled <- port.read.timeout(200.millis).attempt
        _         <- toMaster(pty, "after".getBytes)
        next      <- port.read
      } yield (
        cancelled.left.exists(_.isInstanceOf[TimeoutException]),
        next.map(_.size)
      )
    }.assertEquals((true, Right(5)))
  }

  ptyTest(
    "cancelling a write that is waiting for room leaves the port usable"
  ) { pty =>
    val big = Chunk.array(Array.fill[Byte](200000)(7))
    withPort(pty.slavePath) { port =>
      for {
        cancelled <- port.write(big).timeout(300.millis).attempt
        // what the cancelled write managed to send is already in the line: drain it, so there is room
        after <- (
          port.write(Chunk.array("x".getBytes)),
          drainMaster(pty)
        ).parTupled
      } yield (
        cancelled.left.exists(_.isInstanceOf[TimeoutException]),
        after._1
      )
    }.timeout(30.seconds).assertEquals((true, Right(())))
  }

  /** Reads from the master until it has been quiet for a moment. */
  private def drainMaster(pty: PseudoTerminal.Pty): IO[Unit] =
    posix.select(List(pty.master), Nil, WaitLimit.UpTo(500.millis)).flatMap {
      case Right(Selection(List(_), _)) =>
        Bytes.read(posix, pty.master, 65536) >> drainMaster(pty)
      case _ => IO.unit
    }

  ptyTest(
    "releasing the port while a read is pending ends that read with Closed"
  ) { pty =>
    // Waits for the read to reach `select`, not for a time to pass.
    SelectSpy
      .make(posix)
      .flatMap { case (spy, selecting) =>
        openPort(pty.slavePath, spy).allocated.flatMap {
          case (Right(port), release) =>
            for {
              reading <- port.read.start
              _       <- selecting
              _       <- release
              result  <- reading.joinWithNever.timeout(5.seconds)
            } yield result.map(_.size)
          case (Left(error), _) =>
            IO.raiseError(new AssertionError(s"open failed: $error"))
        }
      }
      .assertEquals(Left(ReadError.Closed))
  }

  ptyTest("a port used after its Resource is released reports Closed") { pty =>
    openPort(pty.slavePath).allocated
      .flatMap {
        case (Right(port), release) =>
          release >> (
            port.read,
            port.write(Chunk.array("x".getBytes))
          ).tupled
        case (Left(error), _) =>
          IO.raiseError(new AssertionError(s"open failed: $error"))
      }
      .map { case (read, written) => (read.map(_.size), written) }
      .assertEquals((Left(ReadError.Closed), Left(WriteError.Closed)))
  }

  test(
    "a device that disappears is Disconnected, and the read stream fails with it"
  ) {
    SelectSpy
      .make(posix)
      .flatMap { case (spy, selecting) =>
        PseudoTerminal.resource(posix).allocated.flatMap {
          case (pty, releasePty) =>
            openPort(pty.slavePath, spy).use {
              case Right(port) =>
                for {
                  reading <- port.read.start
                  _       <- selecting
                  _       <-
                    releasePty // closes the master: the port's device is gone
                  result <- reading.joinWithNever.timeout(5.seconds)
                  stream <- port.reads.compile.drain.attempt
                } yield (result.map(_.size), stream)
              case Left(error) =>
                IO.raiseError(new AssertionError(s"open failed: $error"))
            }
        }
      }
      .assertEquals(
        (
          Left(ReadError.Disconnected),
          Left(SerialException.Read(ReadError.Disconnected))
        )
      )
  }

  ptyTest("a port that is open cannot be opened again, until it is released") {
    pty =>
      def attempt: IO[Either[OpenError, Unit]] =
        openPort(pty.slavePath).use(opened => IO.pure(opened.void))
      for {
        busy  <- openPort(pty.slavePath).use(_ => attempt)
        again <- attempt
      } yield {
        assertEquals(busy, Left(OpenError.PortBusy(pty.slavePath)))
        assertEquals(again, Right(()))
      }
  }

  test(
    "opening a path that does not exist is a value, and there is nothing to release"
  ) {
    openPort("/dev/does-not-exist-fs2-serial")
      .use(opened => IO.pure(opened.void))
      .assertEquals(
        Left(OpenError.NoSuchPort("/dev/does-not-exist-fs2-serial"))
      )
  }
}
