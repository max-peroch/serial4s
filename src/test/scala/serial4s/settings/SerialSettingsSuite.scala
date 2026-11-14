package serial4s.settings

import munit.FunSuite

final class SerialSettingsSuite extends FunSuite {

  test("the default is 115200 8N1 without flow control") {
    val settings = SerialSettings()
    assertEquals(settings.baudRate.value, 115200)
    assertEquals(settings.charSize, CharSize.Eight)
    assertEquals(settings.parity, Parity.NoParity)
    assertEquals(settings.stopBits, StopBits.One)
    assertEquals(settings.flowControl, FlowControl.Off)
  }

  test("a setting can be changed without touching the others") {
    val settings = SerialSettings().copy(parity = Parity.Even)
    assertEquals(settings.parity, Parity.Even)
    assertEquals(settings.charSize, CharSize.Eight)
    assertEquals(settings.baudRate.value, 115200)
  }

  test("a character size reports its number of data bits") {
    assertEquals(CharSize.values.toList.map(_.bits), List(5, 6, 7, 8))
  }
}
