package fs2.serial.settings

import fs2.serial.settings.PollInterval.duration
import munit.FunSuite
import scala.concurrent.duration.*

final class PollIntervalSuite extends FunSuite {

  test(
    "an interval at or above the minimum is accepted and keeps its duration"
  ) {
    List(PollInterval.Minimum, 11.millis, 1.second, 1.hour).foreach { wanted =>
      assertEquals(PollInterval(wanted).map(_.duration), Right(wanted))
    }
  }

  test("a zero, negative or too short interval is refused, and says which") {
    List(Duration.Zero, (-1).second, 1.millis, 9.millis).foreach { wanted =>
      assertEquals(
        PollInterval(wanted),
        Left(SettingError.PollIntervalTooShort(wanted))
      )
    }
  }

  test("the default is one second, and is itself a valid interval") {
    assertEquals(PollInterval.OneSecond.duration, 1.second)
    assertEquals(
      PollInterval(1.second).map(_.duration),
      Right(PollInterval.OneSecond.duration)
    )
  }
}
