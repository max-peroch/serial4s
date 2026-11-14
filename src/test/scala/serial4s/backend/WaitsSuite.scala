package serial4s.backend

import cats.effect.{IO, Outcome}
import munit.CatsEffectSuite

final class WaitsSuite extends CatsEffectSuite {

  private val boom = new RuntimeException("boom")

  test(
    "a wait that stopped by failing is not forgotten: the failure is raised"
  ) {
    Waits
      .settled[IO, Int](Outcome.errored[IO, Throwable, Int](boom))
      .attempt
      .assertEquals(Left(boom))
  }

  test("a wait that was cancelled, or finished, settles quietly") {
    Waits
      .settled[IO, Int](Outcome.canceled[IO, Throwable, Int])
      .assertEquals(()) >>
      Waits
        .settled[IO, Int](Outcome.succeeded[IO, Throwable, Int](IO.pure(1)))
        .assertEquals(())
  }
}
