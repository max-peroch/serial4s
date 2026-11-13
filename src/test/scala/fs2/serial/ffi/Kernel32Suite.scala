package fs2.serial.ffi

import fs2.serial.platform.NativeUnavailable
import cats.effect.IO
import munit.CatsEffectSuite

final class Kernel32Suite extends CatsEffectSuite {

  private val onWindows: Boolean =
    Option(System.getProperty("os.name")).exists(_.startsWith("Windows"))

  test(
    "off Windows, kernel32 cannot be loaded, and that is a value, not an exception"
  ) {
    assume(!onWindows, "this machine is Windows")
    val loaded = Kernel32.load()
    assert(loaded.isLeft, s"expected Left, got $loaded")
    assert(
      loaded.left.exists { case NativeUnavailable(reason) =>
        reason.startsWith("cannot load kernel32")
      },
      loaded.toString
    )
  }

  test("the effectful loader says the same") {
    assume(!onWindows, "this machine is Windows")
    WindowsLib.load[IO].map { loaded =>
      assert(loaded.isLeft, s"expected Left, got ${loaded.map(_ => ())}")
    }
  }
}
