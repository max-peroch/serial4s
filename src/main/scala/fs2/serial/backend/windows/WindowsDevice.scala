package fs2.serial.backend.windows

import fs2.serial.backend.{Device, Waits}
import fs2.serial.errors.{
  OpenError,
  ReadError,
  SystemError,
  WindowsError,
  WriteError
}
import fs2.serial.settings.SerialSettings
import fs2.serial.win32.{Awaited, Event, Handle, Operation, Started, Win32}
import cats.data.EitherT
import cats.effect.{Concurrent, Ref, Resource}
import cats.syntax.all.*
import fs2.Chunk

/** A [[Device]] over [[Win32]]: overlapped I/O, waited on in a worker thread.
  *
  * A transfer is started, then waited on together with a wake event. Cancelling
  * the wait signals the wake event and then cancels the transfer: Microsoft's
  * documentation says a cancelled transfer can still complete normally, so a
  * cancelled read may have taken bytes. They go into `carry` and are the first
  * thing the next read returns: a cancelled read loses nothing.
  *
  * A wake event stays signalled until reset. After a cancel it is stale, so a
  * wait that finds it signalled with the device still open resets it and starts
  * over; the reset comes before the check of the state, so a shutdown that
  * lands in between is not lost. Each direction has its own event.
  */
private[serial] final class WindowsDevice[F[_]] private (
    win32: Win32[F],
    handles: WindowsDevice.Handles[F],
    state: Ref[F, WindowsDevice.PortState],
    carry: Ref[F, Chunk[Byte]]
)(using F: Concurrent[F])
    extends Device[F] {
  import WindowsDevice.*

  def read: F[Either[ReadError, Chunk[Byte]]] = readLoop

  def write(chunk: Chunk[Byte]): F[Either[WriteError, Unit]] =
    writeFrom(chunk.toArray, 0)

  /** Marks the device closing and wakes both directions. */
  def shutdown: F[Unit] =
    state.set(PortState.Closing) *> win32.signal(handles.readWake) *>
      win32.signal(handles.writeWake)

  private def ensureOpen[E](closed: E): EitherT[F, E, Unit] =
    EitherT(state.get.map {
      case PortState.Open    => Right(())
      case PortState.Closing => Left(closed)
    })

  /** After a wake: reset the event, then look at the state. In that order. */
  private def stateAfterWake(wake: Event): F[PortState] =
    win32.reset(wake) *> state.get

  // ---- reading -----------------------------------------------------------

  private def readLoop: F[Either[ReadError, Chunk[Byte]]] =
    (for {
      _       <- ensureOpen[ReadError](ReadError.Closed)
      stashed <- EitherT.liftF[F, ReadError, Chunk[Byte]](
        carry.getAndSet(Chunk.empty)
      )
      chunk <-
        if (stashed.nonEmpty) EitherT.rightT[F, ReadError](stashed)
        else EitherT(readOnce)
    } yield chunk).value

  private def readOnce: F[Either[ReadError, Chunk[Byte]]] =
    handles.readOp.startRead(handles.port, BufferSize).flatMap {
      case Left(error)            => F.pure(Left(WindowsIoErrors.read(error)))
      case Right(Started.Done)    => finishRead
      case Right(Started.Pending) =>
        waitFor(handles.readOp, handles.readWake, cancelRead).flatMap {
          case Left(error) =>
            cancelRead.as(Left(WindowsIoErrors.read(error)))
          case Right(Awaited.Finished) => finishRead
          case Right(Awaited.Woken)    =>
            cancelRead *> stateAfterWake(handles.readWake).flatMap {
              case PortState.Closing => F.pure(Left(ReadError.Closed))
              case PortState.Open    => readLoop
            }
        }
    }

  /** The read has finished: take the bytes. None means the long read timeout
    * ran out, so read again.
    */
  private def finishRead: F[Either[ReadError, Chunk[Byte]]] =
    handles.readOp.complete(handles.port).flatMap {
      case Left(error)  => F.pure(Left(WindowsIoErrors.read(error)))
      case Right(0)     => readLoop
      case Right(count) =>
        handles.readOp.bytes(count).map(bytes => Right(Chunk.array(bytes)))
    }

  /** Cancels the read and keeps whatever it had already taken. */
  private def cancelRead: F[Unit] =
    handles.readOp.cancel(handles.port).flatMap {
      case Right(count) if count > 0 =>
        handles.readOp
          .bytes(count)
          .flatMap(bytes => carry.update(_ ++ Chunk.array(bytes)))
      case _ => F.unit
    }

  // ---- writing -----------------------------------------------------------

  private def writeFrom(
      bytes: Array[Byte],
      from: Int
  ): F[Either[WriteError, Unit]] =
    if (from >= bytes.length) F.pure(Right(()))
    else
      (for {
        _       <- ensureOpen[WriteError](WriteError.Closed)
        written <- EitherT(writeSome(bytes, from))
        done    <- EitherT(writeFrom(bytes, from + written))
      } yield done).value

  /** One write of up to a buffer: how many bytes it moved. */
  private def writeSome(
      bytes: Array[Byte],
      from: Int
  ): F[Either[WriteError, Int]] = {
    val count = Math.min(bytes.length - from, BufferSize)
    handles.writeOp.fill(bytes, from, count) *>
      handles.writeOp.startWrite(handles.port, count).flatMap {
        case Left(error)         => F.pure(Left(WindowsIoErrors.write(error)))
        case Right(Started.Done) => finishWrite
        case Right(Started.Pending) =>
          waitFor(handles.writeOp, handles.writeWake, cancelWrite).flatMap {
            case Left(error) =>
              cancelWrite.as(Left(WindowsIoErrors.write(error)))
            case Right(Awaited.Finished) => finishWrite
            case Right(Awaited.Woken)    =>
              handles.writeOp.cancel(handles.port).flatMap {
                case Left(error) =>
                  F.pure(Left(WindowsIoErrors.write(error)))
                case Right(moved) =>
                  stateAfterWake(handles.writeWake).map {
                    case PortState.Closing => Left(WriteError.Closed)
                    case PortState.Open    => Right(moved)
                  }
              }
          }
      }
  }

  private def finishWrite: F[Either[WriteError, Int]] =
    handles.writeOp
      .complete(handles.port)
      .map(_.left.map(WindowsIoErrors.write))

  private def cancelWrite: F[Unit] =
    handles.writeOp.cancel(handles.port).void

  // ---- waiting -----------------------------------------------------------

  /** The blocking wait, cancellable: cancelling signals the wake event, waits
    * for the wait to stop, and then runs `cleanup` (which cancels the
    * transfer).
    */
  private def waitFor(
      operation: Operation[F],
      wake: Event,
      cleanup: F[Unit]
  ): F[Either[WindowsError, Awaited]] =
    F.uncancelable { poll =>
      F.start(operation.await(wake)).flatMap { fiber =>
        F.onCancel(
          poll(fiber.joinWithNever),
          win32.signal(wake) *> fiber.join.flatMap(Waits.settled(_)) *> cleanup
        )
      }
    }
}

private[serial] object WindowsDevice {

  /** Bytes moved per read and per write. */
  private val BufferSize: Int = 4096

  /** Whether the device is in use or being released. */
  private enum PortState { case Open, Closing }

  /** What a device's operations use. */
  private final case class Handles[F[_]](
      port: Handle,
      readWake: Event,
      writeWake: Event,
      readOp: Operation[F],
      writeOp: Operation[F]
  )

  /** Opens the device. A `Left` leaves nothing to release. Each step is a
    * `Resource`; the first that ends in a `Left` stops the chain.
    */
  def open[F[_]](win32: Win32[F], path: String, settings: SerialSettings)(using
      F: Concurrent[F]
  ): Resource[F, Either[OpenError, Device[F]]] = {
    type R[A] = Resource[F, A]
    def io(error: WindowsError): OpenError =
      OpenError.Io(path, SystemError.Windows(error))
    (for {
      port <- EitherT[R, OpenError, Handle](
        WindowsOpener.open(win32, path, settings)
      )
      readWake  <- EitherT[R, OpenError, Event](win32.event.map(_.left.map(io)))
      writeWake <- EitherT[R, OpenError, Event](
        win32.event.map(_.left.map(io))
      )
      readOp <- EitherT[R, OpenError, Operation[F]](
        win32.operation(BufferSize).map(_.left.map(io))
      )
      writeOp <- EitherT[R, OpenError, Operation[F]](
        win32.operation(BufferSize).map(_.left.map(io))
      )
      state <- EitherT.liftF[R, OpenError, Ref[F, PortState]](
        Resource.eval(Ref.of[F, PortState](PortState.Open))
      )
      carry <- EitherT.liftF[R, OpenError, Ref[F, Chunk[Byte]]](
        Resource.eval(Ref.of[F, Chunk[Byte]](Chunk.empty))
      )
    } yield new WindowsDevice[F](
      win32,
      Handles(port, readWake, writeWake, readOp, writeOp),
      state,
      carry
    ): Device[F]).value
  }
}
