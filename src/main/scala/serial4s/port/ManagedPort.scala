package serial4s.port

import serial4s.backend.{Backend, Device}
import serial4s.errors.{OpenError, ReadError, SerialException, WriteError}
import serial4s.settings.SerialSettings
import cats.effect.std.Mutex
import cats.effect.{Concurrent, Ref, Resource}
import cats.syntax.all.*
import fs2.{Chunk, Pipe, Stream}

/** A [[SerialPort]] over a [[Device]]: what is the same on every operating
  * system.
  *
  * It runs one read and one write at a time, and a read and a write together.
  * It also keeps the one fact no backend may get wrong: once the port is being
  * released, no call reaches the device again. Releasing it is the reverse of
  * opening it: the last thing acquired is [[stop]], so it runs first, makes
  * every pending call finish, and waits until none is running, before the
  * device underneath is released.
  */
private[serial4s] final class ManagedPort[F[_]] private (
    val path: String,
    val settings: SerialSettings,
    device: Device[F],
    state: Ref[F, ManagedPort.PortState],
    readLock: Mutex[F],
    writeLock: Mutex[F]
)(using F: Concurrent[F])
    extends SerialPort[F] {
  import ManagedPort.*

  def read: F[Either[ReadError, Chunk[Byte]]] =
    readLock.lock.surround(whenOpen(ReadError.Closed)(device.read))

  def write(chunk: Chunk[Byte]): F[Either[WriteError, Unit]] =
    writeLock.lock.surround(whenOpen(WriteError.Closed)(device.write(chunk)))

  def reads: Stream[F, Byte] =
    Stream
      .repeatEval(read)
      .evalMap(result => F.fromEither(result.left.map(SerialException.Read(_))))
      .unchunks

  def writes: Pipe[F, Byte, Nothing] =
    _.chunks
      .evalMap(chunk =>
        write(chunk).flatMap(result =>
          F.fromEither(result.left.map(SerialException.Write(_)))
        )
      )
      .drain

  def duplex: Pipe[F, Byte, Byte] =
    in => reads.concurrently(in.through(writes))

  /** Runs `call` unless the port is being released. A backend that forgot to
    * remember it was closed would otherwise use a handle that is gone.
    */
  private def whenOpen[E, A](closed: E)(
      call: => F[Either[E, A]]
  ): F[Either[E, A]] =
    state.get.flatMap {
      case PortState.Open    => call
      case PortState.Closing => F.pure(Left(closed))
    }

  /** Makes every read and write finish, and waits until none is running. */
  private def stop: F[Unit] =
    state.set(PortState.Closing) *> device.shutdown *>
      readLock.lock.surround(writeLock.lock.use_)
}

private[serial4s] object ManagedPort {

  /** Whether the port is in use or being released. */
  private enum PortState { case Open, Closing }

  /** Opens `path` through the backend. A `Left` leaves nothing to release. */
  def open[F[_]](backend: Backend[F], path: String, settings: SerialSettings)(
      using F: Concurrent[F]
  ): Resource[F, Either[OpenError, SerialPort[F]]] =
    backend.open(path, settings).flatMap {
      case Left(error)   => Resource.pure(Left(error))
      case Right(device) =>
        Resource
          .eval(
            for {
              state     <- Ref.of[F, PortState](PortState.Open)
              readLock  <- Mutex[F]
              writeLock <- Mutex[F]
            } yield new ManagedPort[F](
              path,
              settings,
              device,
              state,
              readLock,
              writeLock
            )
          )
          .flatTap(port => Resource.onFinalize(port.stop))
          .map(Right(_))
    }
}
