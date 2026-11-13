package fs2.serial.discovery

import fs2.serial.errors.ListError
import fs2.serial.platform.MacOs
import cats.effect.IO
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite

final class PortListerSuite extends CatsEffectSuite {

  test("a device name is a serial port if it is cu.* or tty.*, with the dot") {
    val serial = List(
      "cu.usbmodem1",
      "tty.usbserial-A1",
      "cu.Bluetooth-Incoming-Port",
      "tty.x"
    )
    val other = List(
      "tty",
      "ttys001",
      "ttyp0",
      "console",
      "null",
      "cu",
      "tty.".dropRight(1),
      "disk0"
    )
    assertEquals(
      serial.map(MacOs.isSerialPortName),
      List.fill(serial.size)(true)
    )
    assertEquals(
      other.map(MacOs.isSerialPortName),
      List.fill(other.size)(false)
    )
  }

  test("the serial ports among some names are paths in the directory, sorted") {
    val names = List("tty.b", "ttys001", "cu.a", "null", "cu.c", "tty")
    assertEquals(
      PortLister.serialPorts(MacOs, "/dev", names),
      List("/dev/cu.a", "/dev/cu.c", "/dev/tty.b")
    )
  }

  test("no names, no ports") {
    assertEquals(PortLister.serialPorts(MacOs, "/dev", Nil), Nil)
  }

  private def withDirectory[A](use: Path => IO[A]): IO[A] =
    IO.blocking(Files.createTempDirectory("fs2-serial-list")).flatMap {
      directory =>
        use(directory).guarantee(IO.blocking {
          // a test may have taken the permissions away
          Files.setPosixFilePermissions(
            directory,
            PosixFilePermissions.fromString("rwx------")
          )
          Files.list(directory).forEach(Files.delete(_))
          Files.delete(directory)
        })
    }

  test("listing a directory returns only its serial ports") {
    withDirectory { directory =>
      IO.blocking(
        List("cu.a", "tty.b", "ttys001", "tty", "plain").foreach(name =>
          Files.createFile(directory.resolve(name))
        )
      ) >> PortLister.listIn[IO](MacOs, directory.toString).map((_, directory))
    }.map { case (listed, directory) =>
      assertEquals(listed, Right(List(s"$directory/cu.a", s"$directory/tty.b")))
    }
  }

  test("a directory that does not exist is NoSuchDirectory") {
    val missing = "/does-not-exist-fs2-serial"
    PortLister
      .listIn[IO](MacOs, missing)
      .assertEquals(Left(ListError.NoSuchDirectory(missing)))
  }

  test("a directory the user may not read is AccessDenied") {
    assume(System.getProperty("user.name") != "root", "root can read anything")
    withDirectory { directory =>
      IO.blocking(
        Files.setPosixFilePermissions(
          directory,
          PosixFilePermissions.fromString("---------")
        )
      ) >> PortLister.listIn[IO](MacOs, directory.toString).map((_, directory))
    }.map { case (listed, directory) =>
      assertEquals(listed, Left(ListError.AccessDenied(directory.toString)))
    }
  }

  test("a path that is a file, not a directory, is Unreadable") {
    IO.blocking(Files.createTempFile("fs2-serial", ".txt"))
      .flatMap { file =>
        PortLister
          .listIn[IO](MacOs, file.toString)
          .map {
            case Left(ListError.Unreadable(path, _)) =>
              Right(path == file.toString)
            case other => Left(other)
          }
          .guarantee(IO.blocking(Files.delete(file)))
      }
      .assertEquals(Right(true))
  }
}
