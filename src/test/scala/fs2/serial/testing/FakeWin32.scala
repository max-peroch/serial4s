package fs2.serial.testing

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.serial.errors.{WindowsError, WindowsErrorName}
import fs2.serial.settings.SerialSettings
import fs2.serial.win32.{
  Awaited,
  Event,
  Handle,
  Operation,
  Started,
  Win32,
  WindowsErrors
}

/** One scripted transfer: how it starts, what it later reports, what bytes it
  * moved, and what cancelling it reports.
  */
private[serial] final case class Start(
    started: Either[WindowsError, Started],
    completed: Either[WindowsError, Int] = Right(0),
    data: Array[Byte] = Array.emptyByteArray,
    cancelled: Either[WindowsError, Int] = Right(0)
)

private[serial] object Start {

  def error(name: WindowsErrorName): WindowsError = WindowsErrors.of(name)

  /** Finishes at once, having moved `data`. */
  def done(data: String): Start =
    Start(Right(Started.Done), Right(data.length), data.getBytes)

  /** Finishes at once, having moved `count` bytes. */
  def moved(count: Int): Start = Start(Right(Started.Done), Right(count))

  /** Runs until the test calls [[FakeOperation.finish]]; then it has moved
    * `data`.
    */
  def pending(data: String): Start =
    Start(Right(Started.Pending), Right(data.length), data.getBytes)

  /** Runs until cancelled; the cancel reports `data` as already moved. */
  def pendingUntilCancelled(data: String): Start =
    Start(
      Right(Started.Pending),
      Right(0),
      data.getBytes,
      Right(data.length)
    )

  def failing(name: WindowsErrorName): Start = Start(Left(error(name)))
}

/** A transfer the test drives. It records what is done to it. */
private[serial] final class FakeOperation private (
    val name: String,
    queue: Ref[IO, List[Start]],
    active: Ref[IO, Start],
    finished: Ref[IO, Deferred[IO, Unit]],
    wakes: Ref[IO, Map[Long, Deferred[IO, Unit]]],
    awaiting: Ref[IO, Deferred[IO, Unit]],
    log: Ref[IO, Vector[String]]
) extends Operation[IO] {

  val size: Int = 4096

  def fill(bytes: Array[Byte], from: Int, count: Int): IO[Unit] =
    log.update(_ :+ s"$name fill from=$from count=$count")

  def bytes(count: Int): IO[Array[Byte]] =
    active.get.map(_.data.take(count))

  private def start(
      kind: String,
      count: Int
  ): IO[Either[WindowsError, Started]] =
    log.update(_ :+ s"$name $kind count=$count") *> queue
      .modify {
        case head :: tail => (tail, Some(head))
        case Nil          => (Nil, None)
      }
      .flatMap {
        case None =>
          IO.raiseError(new AssertionError(s"$name: no scripted transfer left"))
        case Some(next) =>
          for {
            gate <- Deferred[IO, Unit]
            _    <- finished.set(gate)
            _    <- active.set(next)
            _    <- gate.complete(()).whenA(next.started == Right(Started.Done))
          } yield next.started
      }

  def startRead(handle: Handle, count: Int): IO[Either[WindowsError, Started]] =
    start("start-read", count)

  def startWrite(
      handle: Handle,
      count: Int
  ): IO[Either[WindowsError, Started]] =
    start("start-write", count)

  def await(wake: Event): IO[Either[WindowsError, Awaited]] =
    for {
      _       <- log.update(_ :+ s"$name await")
      waiting <- awaiting.get
      _       <- waiting.complete(())
      gate    <- finished.get
      signals <- wakes.get
      woken = signals(wake.value)
      raced   <- IO.race(gate.get, woken.get)
      outcome <- raced match {
        case Left(_) => IO.pure(Awaited.Finished)
        // both ready: the transfer wins, as it does in the real wait
        case Right(_) =>
          gate.tryGet.map(_.fold(Awaited.Woken)(_ => Awaited.Finished))
      }
    } yield Right(outcome)

  def complete(handle: Handle): IO[Either[WindowsError, Int]] =
    log.update(_ :+ s"$name complete") *> active.get.map(_.completed)

  def cancel(handle: Handle): IO[Either[WindowsError, Int]] =
    log.update(_ :+ s"$name cancel") *> active.get.map(_.cancelled)

  /** Test hook: the pending transfer finishes. */
  def finish: IO[Unit] = finished.get.flatMap(_.complete(()).void)

  /** Test hook: completes once [[await]] has been called. */
  def awaited: IO[Unit] = awaiting.get.flatMap(_.get)
}

private[serial] object FakeOperation {

  def apply(
      name: String,
      starts: List[Start],
      wakes: Ref[IO, Map[Long, Deferred[IO, Unit]]],
      log: Ref[IO, Vector[String]]
  ): IO[FakeOperation] =
    for {
      queue    <- Ref.of[IO, List[Start]](starts)
      active   <- Ref.of[IO, Start](Start(Right(Started.Done)))
      gate     <- Deferred[IO, Unit]
      finished <- Ref.of[IO, Deferred[IO, Unit]](gate)
      waiting  <- Deferred[IO, Unit]
      awaiting <- Ref.of[IO, Deferred[IO, Unit]](waiting)
    } yield new FakeOperation(
      name,
      queue,
      active,
      finished,
      wakes,
      awaiting,
      log
    )
}

/** A [[Win32]] that does nothing but record, with the operations the test hands
  * it (the first is the read, the second the write).
  */
private[serial] final class FakeWin32 private (
    val read: FakeOperation,
    val write: FakeOperation,
    operations: Ref[IO, List[FakeOperation]],
    wakes: Ref[IO, Map[Long, Deferred[IO, Unit]]],
    nextEvent: Ref[IO, Long],
    val log: Ref[IO, Vector[String]],
    openResult: Either[WindowsError, Handle],
    configureResult: Either[WindowsError, Unit],
    devices: Either[WindowsError, List[String]]
) extends Win32[IO] {

  def open(path: String): IO[Either[WindowsError, Handle]] =
    log.update(_ :+ s"open $path").as(openResult)

  def close(handle: Handle): IO[Either[WindowsError, Unit]] =
    log.update(_ :+ "close").as(Right(()))

  def configure(
      handle: Handle,
      settings: SerialSettings
  ): IO[Either[WindowsError, Unit]] =
    log.update(_ :+ "configure").as(configureResult)

  def readSettings(handle: Handle): IO[Either[WindowsError, SerialSettings]] =
    IO.pure(Right(SerialSettings()))

  def event: Resource[IO, Either[WindowsError, Event]] =
    Resource.eval(
      for {
        id   <- nextEvent.getAndUpdate(_ + 1)
        gate <- Deferred[IO, Unit]
        _    <- wakes.update(_ + (id -> gate))
      } yield Right(Event(id))
    )

  def signal(event: Event): IO[Unit] =
    log.update(_ :+ s"signal ${event.value}") *>
      wakes.get.flatMap(_(event.value).complete(())).void

  def reset(event: Event): IO[Unit] =
    log.update(_ :+ s"reset ${event.value}") *>
      Deferred[IO, Unit].flatMap(fresh =>
        wakes.update(_ + (event.value -> fresh))
      )

  def operation(size: Int): Resource[IO, Either[WindowsError, Operation[IO]]] =
    Resource.eval(
      operations.modify {
        case head :: tail => (tail, Right(head: Operation[IO]))
        case Nil          =>
          (Nil, Left(Start.error(WindowsErrorName.ERROR_NOT_SUPPORTED)))
      }
    )

  def dosDevices: IO[Either[WindowsError, List[String]]] = IO.pure(devices)
}

private[serial] object FakeWin32 {

  def apply(
      reads: List[Start] = Nil,
      writes: List[Start] = Nil,
      openResult: Either[WindowsError, Handle] = Right(Handle(1L)),
      configureResult: Either[WindowsError, Unit] = Right(()),
      devices: Either[WindowsError, List[String]] = Right(Nil)
  ): IO[FakeWin32] =
    for {
      log   <- Ref.of[IO, Vector[String]](Vector.empty)
      wakes <- Ref.of[IO, Map[Long, Deferred[IO, Unit]]](Map.empty)
      read  <- FakeOperation("read", reads, wakes, log)
      write <- FakeOperation("write", writes, wakes, log)
      ops   <- Ref.of[IO, List[FakeOperation]](List(read, write))
      next  <- Ref.of[IO, Long](100L)
    } yield new FakeWin32(
      read,
      write,
      ops,
      wakes,
      next,
      log,
      openResult,
      configureResult,
      devices
    )
}
