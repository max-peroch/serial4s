package serial4s.backend.posix

import serial4s.backend.{Device, Waits}
import serial4s.errors.{
  Errno,
  ErrnoName,
  OpenError,
  ReadError,
  SystemError,
  WriteError
}
import serial4s.posix.{Fd, IoBuffer, Posix, ReadOutcome, WaitLimit}
import serial4s.settings.SerialSettings
import cats.data.EitherT
import cats.effect.{Concurrent, Ref, Resource}
import cats.syntax.all.*
import fs2.Chunk

/** A [[Device]] over [[Posix]].
  *
  * A wait is a blocking `select` on a worker thread, on the port and on a wake
  * pipe. Cancelling the wait writes a byte to the pipe and waits for the
  * `select` to return, so nothing is still using the port when the caller moves
  * on. The byte is left in the pipe: the next wait that finds it drains it and
  * waits again. That is the only place a wake is drained. Each direction has
  * its own pipe: cancelling a read must not wake a write.
  */
private[serial4s] final class PosixDevice[F[_]] private (
    posix: Posix[F],
    handles: PosixDevice.Handles[F],
    state: Ref[F, PosixDevice.PortState]
)(using F: Concurrent[F])
    extends Device[F] {
  import PosixDevice.*

  def read: F[Either[ReadError, Chunk[Byte]]] = readLoop

  def write(chunk: Chunk[Byte]): F[Either[WriteError, Unit]] =
    writeFrom(chunk.toArray, 0)

  /** Marks the device closing and wakes both directions, so each pending call
    * finishes with `Closed`.
    */
  def shutdown: F[Unit] =
    state.set(PortState.Closing) *> interrupt(handles.readWake) *> interrupt(
      handles.writeWake
    )

  /** Fails with `closed` once the device is being released. */
  private def ensureOpen[E](closed: E): EitherT[F, E, Unit] =
    EitherT(state.get.map {
      case PortState.Open    => Right(())
      case PortState.Closing => Left(closed)
    })

  private def readLoop: F[Either[ReadError, Chunk[Byte]]] =
    (for {
      _      <- ensureOpen[ReadError](ReadError.Closed)
      wakeup <- EitherT(await(Direction.Read, handles.readWake))
        .leftMap(IoErrors.read)
      chunk <- wakeup match {
        case Wakeup.Closing =>
          EitherT.leftT[F, Chunk[Byte]](ReadError.Closed: ReadError)
        case Wakeup.Retry => EitherT(readLoop)
        case Wakeup.Ready => EitherT(readReady)
      }
    } yield chunk).value

  /** The port is readable: take what is there. */
  private def readReady: F[Either[ReadError, Chunk[Byte]]] =
    handles.readBuffer.readFrom(handles.port, BufferSize).flatMap {
      case Right(ReadOutcome.EndOfFile) =>
        F.pure(Left(ReadError.Disconnected))
      case Right(ReadOutcome.Data(count)) =>
        handles.readBuffer
          .bytes(count)
          .map(bytes => Right(Chunk.array(bytes)))
      case Left(errno) if isAgain(errno) => readLoop
      case Left(errno)                   => F.pure(Left(IoErrors.read(errno)))
    }

  private def writeFrom(
      bytes: Array[Byte],
      from: Int
  ): F[Either[WriteError, Unit]] =
    if (from >= bytes.length) F.pure(Right(()))
    else
      (for {
        _    <- ensureOpen[WriteError](WriteError.Closed)
        step <- EitherT(writeSome(bytes, from))
        done <- step match {
          case WriteStep.Wrote(count) => EitherT(writeFrom(bytes, from + count))
          case WriteStep.Full         => EitherT(awaitRoom(bytes, from))
        }
      } yield done).value

  /** One `write`: what it took, or that the line is full. */
  private def writeSome(
      bytes: Array[Byte],
      from: Int
  ): F[Either[WriteError, WriteStep]] = {
    val count = Math.min(bytes.length - from, BufferSize)
    (handles.writeBuffer.fill(bytes, from, count) *>
      handles.writeBuffer.writeTo(handles.port, count)).map {
      case Right(written) if written > 0 => Right(WriteStep.Wrote(written))
      case Right(_)                      => Right(WriteStep.Full)
      case Left(errno) if isAgain(errno) => Right(WriteStep.Full)
      case Left(errno)                   => Left(IoErrors.write(errno))
    }
  }

  private def awaitRoom(
      bytes: Array[Byte],
      from: Int
  ): F[Either[WriteError, Unit]] =
    (for {
      wakeup <- EitherT(await(Direction.Write, handles.writeWake))
        .leftMap(IoErrors.write)
      done <- wakeup match {
        case Wakeup.Closing =>
          EitherT.leftT[F, Unit](WriteError.Closed: WriteError)
        case Wakeup.Retry | Wakeup.Ready => EitherT(writeFrom(bytes, from))
      }
    } yield done).value

  /** Waits until the port can be read or written, or until something wakes the
    * wait.
    */
  private def await(
      direction: Direction,
      wake: Wake[F]
  ): F[Either[Errno, Wakeup]] =
    select(direction, wake).flatMap {
      case Left(errno)          => F.pure(Left(errno))
      case Right(Selected.Port) => F.pure(Right(Wakeup.Ready))
      case Right(Selected.Wake) =>
        state.get.flatMap {
          case PortState.Closing => F.pure(Right(Wakeup.Closing))
          case PortState.Open    => drain(wake).as(Right(Wakeup.Retry))
        }
    }

  /** The blocking `select`, cancellable: cancelling wakes it and waits for it.
    */
  private def select(
      direction: Direction,
      wake: Wake[F]
  ): F[Either[Errno, Selected]] =
    F.uncancelable { poll =>
      val (reads, writes) = direction match {
        case Direction.Read =>
          (List(handles.port, wake.readEnd), List.empty[Fd])
        case Direction.Write => (List(wake.readEnd), List(handles.port))
      }
      val wait = posix
        .select(reads, writes, WaitLimit.Unlimited)
        .map(
          _.map(ready =>
            if (ready.readable.contains(wake.readEnd)) Selected.Wake
            else Selected.Port
          )
        )
      F.start(wait).flatMap { fiber =>
        F.onCancel(
          poll(fiber.joinWithNever),
          interrupt(wake) *> fiber.join.flatMap(Waits.settled(_))
        )
      }
    }

  private def interrupt(wake: Wake[F]): F[Unit] =
    handles.wakeByte.writeTo(wake.writeEnd, 1).void

  /** Empties a wake pipe. It is non-blocking: an empty pipe answers `EAGAIN`.
    */
  private def drain(wake: Wake[F]): F[Unit] =
    wake.scratch.readFrom(wake.readEnd, DrainSize).flatMap {
      case Right(ReadOutcome.Data(_)) => drain(wake)
      case _                          => F.unit
    }
}

private[serial4s] object PosixDevice {

  /** Bytes moved per read and per write call. */
  private val BufferSize: Int = 4096

  /** Bytes read from a wake pipe at a time while emptying it. */
  private val DrainSize: Int = 16

  /** Whether the device is in use or being released. */
  private enum PortState { case Open, Closing }

  private enum Direction { case Read, Write }

  /** What the `select` found ready. */
  private enum Selected { case Port, Wake }

  /** What a wait means for the caller. */
  private enum Wakeup {

    /** The port is ready: go on. */
    case Ready

    /** Woken for nothing the caller cares about: wait again. */
    case Retry

    /** The device is being released: stop. */
    case Closing
  }

  /** What one `write` call did. */
  private enum WriteStep {

    /** It took `count` bytes. */
    case Wrote(count: Int)

    /** The line is full: wait until there is room. */
    case Full
  }

  private def isAgain(errno: Errno): Boolean = errno.name == ErrnoName.EAGAIN

  /** The two ends of a wake pipe, and a small buffer to drain it into. */
  private final case class Wake[F[_]](
      readEnd: Fd,
      writeEnd: Fd,
      scratch: IoBuffer[F]
  )

  /** What a device's operations use. */
  private final case class Handles[F[_]](
      port: Fd,
      readWake: Wake[F],
      writeWake: Wake[F],
      readBuffer: IoBuffer[F],
      writeBuffer: IoBuffer[F],
      wakeByte: IoBuffer[F]
  )

  /** Opens the device. A `Left` leaves nothing to release. Each step is a
    * `Resource`; the first one that ends in a `Left` stops the chain, and what
    * was acquired before it is released.
    */
  def open[F[_]](posix: Posix[F], path: String, settings: SerialSettings)(using
      F: Concurrent[F]
  ): Resource[F, Either[OpenError, Device[F]]] = {
    type R[A] = Resource[F, A]
    (for {
      port <- EitherT[R, OpenError, Fd](PortOpener.open(posix, path, settings))
      readWake  <- EitherT[R, OpenError, Wake[F]](wakePipe(posix, path))
      writeWake <- EitherT[R, OpenError, Wake[F]](wakePipe(posix, path))
      handles   <- EitherT.liftF[R, OpenError, Handles[F]](
        (
          posix.buffer(BufferSize),
          posix.buffer(BufferSize),
          wakeByteBuffer(posix)
        ).mapN(Handles(port, readWake, writeWake, _, _, _))
      )
      state <- EitherT.liftF[R, OpenError, Ref[F, PortState]](
        Resource.eval(Ref.of[F, PortState](PortState.Open))
      )
    } yield new PosixDevice[F](posix, handles, state): Device[F]).value
  }

  /** One byte of value 1: what is written to a wake pipe. */
  private def wakeByteBuffer[F[_]](posix: Posix[F]): Resource[F, IoBuffer[F]] =
    posix.buffer(1).evalTap(_.fill(Array(1.toByte), 0, 1))

  private def wakePipe[F[_]](posix: Posix[F], path: String)(using
      F: Concurrent[F]
  ): Resource[F, Either[OpenError, Wake[F]]] =
    Resource
      .make(posix.pipe())(_.traverse_ { case (readEnd, writeEnd) =>
        posix.close(readEnd) *> posix.close(writeEnd).void
      })
      .flatMap {
        case Left(errno) =>
          Resource.pure(Left(OpenError.Io(path, SystemError.Posix(errno))))
        case Right((readEnd, writeEnd)) =>
          posix
            .buffer(DrainSize)
            .map(scratch => Right(Wake(readEnd, writeEnd, scratch)))
      }
}
