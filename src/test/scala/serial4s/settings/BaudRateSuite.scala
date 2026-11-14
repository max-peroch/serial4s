package serial4s.settings

import munit.FunSuite

final class BaudRateSuite extends FunSuite {

  test("the standard rates are exactly the POSIX ones, in ascending order") {
    val posix = Vector(50, 75, 110, 134, 150, 200, 300, 600, 1200, 1800, 2400,
      4800, 9600, 19200, 38400, 57600, 115200, 230400)
    assertEquals(BaudRate.standard, posix)
    assertEquals(BaudRate.standard, BaudRate.standard.sorted.distinct)
  }

  test("every standard rate is accepted, keeps its value and is standard") {
    BaudRate.standard.foreach { rate =>
      assertEquals(BaudRate(rate).map(_.value), Right(rate), rate.toString)
      assertEquals(BaudRate(rate).map(_.isStandard), Right(true), rate.toString)
    }
  }

  test(
    "a rate between the limits that is not standard is accepted, and is not standard"
  ) {
    List(51, 12345, 250000, 460800, 921600, 3000000).foreach { rate =>
      assertEquals(BaudRate(rate).map(_.value), Right(rate), rate.toString)
      assertEquals(
        BaudRate(rate).map(_.isStandard),
        Right(false),
        rate.toString
      )
    }
  }

  test("the limits themselves are accepted, and one beyond either is refused") {
    assertEquals(
      BaudRate(BaudRate.Minimum).map(_.value),
      Right(BaudRate.Minimum)
    )
    assertEquals(
      BaudRate(BaudRate.Maximum).map(_.value),
      Right(BaudRate.Maximum)
    )
    List(BaudRate.Minimum - 1, BaudRate.Maximum + 1).foreach { rate =>
      assertEquals(
        BaudRate(rate),
        Left(SettingError.BaudRateOutOfRange(rate)),
        rate.toString
      )
    }
  }

  test(
    "zero, negative and absurd rates are refused, and the error says which one"
  ) {
    List(0, 1, 49, -1, -9600, Int.MinValue, Int.MaxValue, 4000001).foreach {
      rate =>
        assertEquals(
          BaudRate(rate),
          Left(SettingError.BaudRateOutOfRange(rate)),
          rate.toString
        )
    }
  }

  test("exactly the integers from the minimum to the maximum are accepted") {
    val accepted = (-10 to BaudRate.Maximum + 10).count(BaudRate(_).isRight)
    assertEquals(accepted, BaudRate.Maximum - BaudRate.Minimum + 1)
  }

  test("the named constants are standard rates") {
    val named = List(
      BaudRate.B9600,
      BaudRate.B19200,
      BaudRate.B38400,
      BaudRate.B57600,
      BaudRate.B115200,
      BaudRate.B230400
    )
    assert(named.forall(_.isStandard))
    assertEquals(
      named.map(_.value),
      List(9600, 19200, 38400, 57600, 115200, 230400)
    )
  }
}
