package serial4s.discovery

import serial4s.errors.{ListError, SerialException}
import serial4s.settings.PollInterval
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import scala.concurrent.duration.*

final class PortWatcherSuite extends CatsEffectSuite {

  private val a = "/dev/cu.a"
  private val b = "/dev/cu.b"
  private val c = "/dev/cu.c"

  private def interval(duration: FiniteDuration): PollInterval =
    PollInterval(duration).fold(
      error => fail(s"bad interval: $error"),
      identity
    )

  test(
    "diff reports the ports that appeared, then those that went, each sorted"
  ) {
    assertEquals(
      PortWatcher.diff(Set(a, b), Set(b, c, "/dev/cu.d")),
      List(
        PortEvent.Appeared(c),
        PortEvent.Appeared("/dev/cu.d"),
        PortEvent.Disappeared(a)
      )
    )
  }

  test("diff of identical sets is empty, and of empty to empty too") {
    assertEquals(PortWatcher.diff(Set(a, b), Set(b, a)), Nil)
    assertEquals(PortWatcher.diff(Set.empty, Set.empty), Nil)
  }

  private type Listing = Either[ListError, List[String]]

  test(
    "the watcher reports changes between polls, and not what was there at the start"
  ) {
    val program = for {
      listing <- Ref.of[IO, Listing](Right(List(a)))
      _       <- (IO.sleep(1500.millis) *> listing.set(Right(List(a, b))) *>
        IO.sleep(1.second) *> listing.set(Right(List(b)))).start
      events <- PortWatcher
        .events(listing.get, interval(1.second))
        .take(2)
        .compile
        .toList
    } yield events
    TestControl
      .executeEmbed(program)
      .assertEquals(List(PortEvent.Appeared(b), PortEvent.Disappeared(a)))
  }

  test("a quiet directory produces no events however long it is watched") {
    val program = for {
      listing <- Ref.of[IO, Listing](Right(List(a)))
      events  <- PortWatcher
        .events(listing.get, interval(1.second))
        .interruptAfter(1.minute)
        .compile
        .toList
    } yield events
    TestControl.executeEmbed(program).assertEquals(Nil)
  }

  test(
    "a listing that fails after the baseline fails the stream with the scoped error"
  ) {
    val denied  = ListError.AccessDenied("/dev")
    val program = for {
      listing <- Ref.of[IO, Listing](Right(List(a)))
      _       <- (IO.sleep(1500.millis) *> listing.set(Left(denied))).start
      result  <- PortWatcher
        .events(listing.get, interval(1.second))
        .compile
        .toList
        .attempt
    } yield result
    TestControl
      .executeEmbed(program)
      .assertEquals(Left(SerialException.Listing(denied)))
  }

  test("a baseline that cannot be listed fails the stream at once") {
    val missing = ListError.NoSuchDirectory("/dev")
    PortWatcher
      .events[IO](IO.pure(Left(missing)), interval(1.second))
      .compile
      .toList
      .attempt
      .assertEquals(Left(SerialException.Listing(missing)))
  }
}
