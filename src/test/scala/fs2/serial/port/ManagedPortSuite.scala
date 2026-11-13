package fs2.serial.port

import fs2.serial.backend.{Backend, Device}
import fs2.serial.errors.{
  ListError,
  OpenError,
  ReadError,
  SerialException,
  WriteError
}
import fs2.serial.settings.SerialSettings
import cats.effect.testkit.TestControl
import cats.effect.std.Queue
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** What is the same on every operating system, over a pretend [[Device]]: no
  * I/O, so time is virtual.
  */
final class ManagedPortSuite extends CatsEffectSuite {

  private def text(chunk: Chunk[Byte]): String = new String(chunk.toArray)

  /** A device that logs what happens to it. Reads come from `reads`, and wait
    * for `shutdown` once it is empty; a write takes one virtual second.
    */
  private final class Fake(
      log: Ref[IO, Vector[String]],
      reads: Ref[IO, List[Either[ReadError, Chunk[Byte]]]],
      writeOutcome: Either[WriteError, Unit],
      closed: Deferred[IO, Unit]
  ) extends Device[IO] {

    def read: IO[Either[ReadError, Chunk[Byte]]] =
      log.update(_ :+ "read-start") *>
        reads
          .modify {
            case head :: tail => (tail, Some(head))
            case Nil          => (Nil, None)
          }
          .flatMap {
            case Some(result) => IO.pure(result)
            case None         => closed.get.as(Left(ReadError.Closed))
          }
          .flatTap(_ => log.update(_ :+ "read-end"))

    def write(chunk: Chunk[Byte]): IO[Either[WriteError, Unit]] =
      log.update(_ :+ s"write-begin ${text(chunk)}") *> IO.sleep(1.second) *>
        log.update(_ :+ s"write-end ${text(chunk)}").as(writeOutcome)

    def shutdown: IO[Unit] =
      log.update(_ :+ "shutdown") *> closed.complete(()).void
  }

  private final case class Rig(
      backend: Backend[IO],
      log: Ref[IO, Vector[String]]
  )

  private def rig(
      reads: List[Either[ReadError, Chunk[Byte]]] = Nil,
      writeOutcome: Either[WriteError, Unit] = Right(())
  ): IO[Rig] =
    for {
      log      <- Ref.of[IO, Vector[String]](Vector.empty)
      scripted <- Ref.of[IO, List[Either[ReadError, Chunk[Byte]]]](reads)
      closed   <- Deferred[IO, Unit]
      device = new Fake(log, scripted, writeOutcome, closed)
    } yield Rig(
      new Backend[IO] {
        def open(path: String, settings: SerialSettings) =
          Resource.make(IO.pure(Right(device): Either[OpenError, Device[IO]]))(
            _ => log.update(_ :+ "device-released")
          )
        def list: IO[Either[ListError, List[String]]] = IO.pure(Right(Nil))
      },
      log
    )

  private def opened(r: Rig): IO[(SerialPort[IO], IO[Unit])] =
    ManagedPort
      .open[IO](r.backend, "COM1", SerialSettings())
      .allocated
      .flatMap {
        case (Right(port), release) => IO.pure((port, release))
        case (Left(error), _)       =>
          IO.raiseError(new AssertionError(s"open: $error"))
      }

  test(
    "two writes never interleave: each goes through whole, one after the other"
  ) {
    TestControl
      .executeEmbed(
        for {
          r               <- rig()
          (port, release) <- opened(r)
          _               <- (
            port.write(Chunk.array("aa".getBytes)),
            port.write(Chunk.array("bb".getBytes))
          ).parTupled
          _       <- release
          entries <- r.log.get
        } yield entries.filter(_.startsWith("write"))
      )
      .map { writes =>
        val pairs = writes.grouped(2).toList
        assertEquals(pairs.size, 2)
        pairs.foreach { pair =>
          assertEquals(pair.size, 2)
          assertEquals(pair.last, pair.head.replace("begin", "end"))
        }
      }
  }

  test("a read and a write run together") {
    TestControl
      .executeEmbed(
        for {
          r <- rig(reads = List(Right(Chunk.array("in".getBytes))))
          (port, release) <- opened(r)
          both            <- (
            port.read,
            port.write(Chunk.array("out".getBytes))
          ).parTupled
          _       <- release
          entries <- r.log.get
        } yield (both._1.map(text), both._2, entries)
      )
      .map { case (read, written, entries) =>
        assertEquals(read, Right("in"))
        assertEquals(written, Right(()))
        // the read started before the write finished: they were not run one after the other
        assert(
          entries.indexOf("read-start") < entries.indexOf("write-end out"),
          entries.toString
        )
      }
  }

  test("only one read runs at a time: the second waits for the first") {
    TestControl
      .executeEmbed(
        for {
          r <- rig() // nothing to read: a read blocks until shutdown
          (port, release)      <- opened(r)
          first                <- port.read.start
          second               <- port.read.start
          _                    <- IO.sleep(1.second)
          startedBeforeRelease <- r.log.get.map(_.count(_ == "read-start"))
          _                    <- release
          _       <- (first.joinWithNever, second.joinWithNever).tupled
          entries <- r.log.get
        } yield (startedBeforeRelease, entries.count(_ == "read-start"))
      )
      // the second read waited for the first; by the time it got the lock the port was closed,
      // so it never reached the device
      .assertEquals((1, 1))
  }

  test(
    "releasing shuts the device down, waits for a pending read to end, then releases the device"
  ) {
    TestControl
      .executeEmbed(
        for {
          r               <- rig()
          (port, release) <- opened(r)
          reading         <- port.read.start
          _       <- IO.sleep(1.second) // the read is now waiting in the device
          _       <- release
          result  <- reading.joinWithNever
          entries <- r.log.get
        } yield (result.map(text), entries)
      )
      .assertEquals(
        (
          Left(ReadError.Closed),
          Vector("read-start", "shutdown", "read-end", "device-released")
        )
      )
  }

  test(
    "a read stream gives the bytes in order, and fails with the scoped error when the device does"
  ) {
    TestControl
      .executeEmbed(
        for {
          r <- rig(
            reads = List(
              Right(Chunk.array(Array[Byte](1, 2))),
              Right(Chunk.array(Array[Byte](3))),
              Left(ReadError.Disconnected)
            )
          )
          (port, release) <- opened(r)
          outcome         <- port.reads.compile.toList.attempt
          _               <- release
        } yield outcome
      )
      .assertEquals(Left(SerialException.Read(ReadError.Disconnected)))
  }

  test("a read stream is flattened: the bytes of every chunk, one by one") {
    TestControl
      .executeEmbed(
        for {
          r <- rig(
            reads = List(
              Right(Chunk.array(Array[Byte](1, 2))),
              Right(Chunk.array(Array[Byte](3)))
            )
          )
          (port, release) <- opened(r)
          taken           <- port.reads.take(3).compile.toList
          _               <- release
        } yield taken
      )
      .assertEquals(List[Byte](1, 2, 3))
  }

  test(
    "the write pipe sends every chunk in order, and fails with the scoped error when the device does"
  ) {
    TestControl
      .executeEmbed(
        for {
          ok                  <- rig()
          (okPort, releaseOk) <- opened(ok)
          _                   <- Stream
            .emits("abc".getBytes)
            .covary[IO]
            .through(okPort.writes)
            .compile
            .drain
          _    <- releaseOk
          sent <- ok.log.get.map(_.filter(_.startsWith("write-end")))
          bad  <- rig(writeOutcome = Left(WriteError.Disconnected))
          (badPort, releaseBad) <- opened(bad)
          failed                <- Stream
            .emits("x".getBytes)
            .covary[IO]
            .through(badPort.writes)
            .compile
            .drain
            .attempt
          _ <- releaseBad
        } yield (sent.map(_.stripPrefix("write-end ")).mkString, failed)
      )
      .assertEquals(
        ("abc", Left(SerialException.Write(WriteError.Disconnected)))
      )
  }

  test("a device that cannot be opened is a value, and nothing is released") {
    val error: OpenError = OpenError.NoSuchPort("COM9")
    val backend          = new Backend[IO] {
      def open(path: String, settings: SerialSettings) =
        Resource.pure(Left(error))
      def list: IO[Either[ListError, List[String]]] = IO.pure(Right(Nil))
    }
    ManagedPort
      .open[IO](backend, "COM9", SerialSettings())
      .use(opened => IO.pure(opened.map(_ => ())))
      .assertEquals(Left(error))
  }

  /** A device that gives back what it is sent, like a loopback with echo. */
  private def echoBackend(
      writeOutcome: Either[WriteError, Unit] = Right(())
  ): IO[Backend[IO]] =
    for {
      queue  <- Queue.unbounded[IO, Chunk[Byte]]
      closed <- Deferred[IO, Unit]
    } yield new Backend[IO] {
      private val device = new Device[IO] {
        def read: IO[Either[ReadError, Chunk[Byte]]] =
          IO.race(queue.take, closed.get).map {
            case Left(chunk) => Right(chunk)
            case Right(_)    => Left(ReadError.Closed)
          }
        def write(chunk: Chunk[Byte]): IO[Either[WriteError, Unit]] =
          queue.offer(chunk).as(writeOutcome)
        def shutdown: IO[Unit] = closed.complete(()).void
      }
      def open(path: String, settings: SerialSettings) =
        Resource.pure(Right(device): Either[OpenError, Device[IO]])
      def list: IO[Either[ListError, List[String]]] = IO.pure(Right(Nil))
    }

  private def echoPort(backend: Backend[IO]): Resource[IO, SerialPort[IO]] =
    ManagedPort.open[IO](backend, "COM1", SerialSettings()).evalMap {
      case Right(port) => IO.pure(port)
      case Left(error) => IO.raiseError(new AssertionError(s"open: $error"))
    }

  test("duplex writes what comes in and emits what arrives, at the same time") {
    TestControl
      .executeEmbed(
        echoBackend().flatMap(backend =>
          echoPort(backend).use(port =>
            Stream
              .emits("hello".getBytes)
              .covary[IO]
              .through(port.duplex)
              .take(5)
              .compile
              .toList
          )
        )
      )
      .map(bytes => new String(bytes.toArray))
      .assertEquals("hello")
  }

  test("a write that fails fails the duplex with the scoped error") {
    TestControl
      .executeEmbed(
        echoBackend(Left(WriteError.Disconnected)).flatMap(backend =>
          echoPort(backend).use(port =>
            Stream
              .emits("x".getBytes)
              .covary[IO]
              .through(port.duplex)
              .compile
              .drain
              .attempt
          )
        )
      )
      .assertEquals(Left(SerialException.Write(WriteError.Disconnected)))
  }

  test("after release no call reaches the device, whatever the device does") {
    TestControl
      .executeEmbed(
        for {
          r               <- rig()
          (port, release) <- opened(r)
          _               <- release
          before          <- r.log.get
          read            <- port.read
          written         <- port.write(Chunk.array("late".getBytes))
          after           <- r.log.get
        } yield (read.map(_.size), written, after.drop(before.size))
      )
      .assertEquals(
        (Left(ReadError.Closed), Left(WriteError.Closed), Vector.empty)
      )
  }
}
