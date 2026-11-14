package serial4s.platform

import munit.FunSuite

final class OsSuite extends FunSuite {

  test("the JVM's os.name chooses the family") {
    assertEquals(Os.forSystem("Mac OS X"), Right(Os.Mac))
    assertEquals(Os.forSystem("Linux"), Right(Os.Linux))
  }

  test("every Windows os.name is Windows, whatever the version") {
    List("Windows 10", "Windows 11", "Windows Server 2022", "Windows 7")
      .foreach(name =>
        assertEquals(Os.forSystem(name), Right(Os.Windows), name)
      )
  }

  test("any other system is refused, and the reason names it") {
    List("FreeBSD", "SunOS", "AIX", "unknown").foreach(name =>
      assertEquals(
        Os.forSystem(name),
        Left(NativeUnavailable(s"unsupported operating system: $name")),
        name
      )
    )
  }

  test("a name that merely contains Windows is not Windows") {
    assertEquals(
      Os.forSystem("Not Windows"),
      Left(NativeUnavailable("unsupported operating system: Not Windows"))
    )
  }
}
