package fs2.serial.ffi

import cats.effect.IO
import java.lang.foreign.{FunctionDescriptor, Linker, ValueLayout}
import munit.CatsEffectSuite
import scala.jdk.OptionConverters.*

/** Proves the build can call libc through FFM under
  * `--illegal-native-access=deny`.
  */
final class NativeAccessSuite extends CatsEffectSuite:

  // On Windows the C runtime names it `_getpid`.
  private val getpidName: String =
    if Option(System.getProperty("os.name")).exists(_.startsWith("Windows"))
    then "_getpid"
    else "getpid"

  test("getpid from the C library matches the JVM's process id") {
    val linker = Linker.nativeLinker()
    val getpid = for
      symbol <- IO.fromOption(linker.defaultLookup().find(getpidName).toScala)(
        new NoSuchElementException(
          s"$getpidName not found in the default lookup"
        )
      )
      handle = linker.downcallHandle(
        symbol,
        FunctionDescriptor.of(ValueLayout.JAVA_INT)
      )
      pid <- IO.delay(handle.invokeExact(): Int)
    yield pid
    getpid.assertEquals(ProcessHandle.current().pid().toInt)
  }
