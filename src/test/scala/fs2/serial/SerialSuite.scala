package fs2.serial

import fs2.serial.errors.{OpenError, PlatformError, SerialException}
import fs2.serial.platform.{Host, Os}
import fs2.serial.testing.{Bytes, PosixFixture, PseudoTerminal}
import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite

/** The public entry point: a `Serial` made once, then used. */
final class SerialSuite extends CatsEffectSuite with PosixFixture {

  private val serial: IO[Serial[IO]] =
    Serial
      .make[IO]
      .flatMap(
        _.fold(
          error => IO.raiseError(new AssertionError(s"make: $error")),
          IO.pure
        )
      )

  test("a Serial can be made on this machine") {
    Serial
      .make[IO]
      .map(made => assert(made.isRight, s"expected a Serial, got $made"))
  }

  test("a port that does not exist is a value, with nothing to release") {
    serial
      .flatMap(
        _.open("/dev/does-not-exist-fs2-serial")
          .use(opened => IO.pure(opened.void))
      )
      .assertEquals(
        Left(OpenError.NoSuchPort("/dev/does-not-exist-fs2-serial"))
      )
  }

  test("one Serial opens a port, and opens it again once it is released") {
    PseudoTerminal
      .resource(posix)
      .use { pty =>
        serial.flatMap { s =>
          for {
            first <- s.open(pty.slavePath).use {
              case Right(port) =>
                Stream
                  .emits("hi".getBytes)
                  .covary[IO]
                  .through(port.writes)
                  .compile
                  .drain >>
                  Bytes.read(posix, pty.master, 16).map(_.map(new String(_)))
              case Left(error) =>
                IO.raiseError(new AssertionError(s"open: $error"))
            }
            second <- s
              .open(pty.slavePath)
              .use(opened => IO.pure(opened.isRight))
          } yield (first, second)
        }
      }
      .assertEquals((Right("hi"), true))
  }

  test("list returns sorted serial device paths, and opens nothing") {
    serial.flatMap(_.list).map {
      case Right(ports) =>
        assert(
          ports
            .forall(p => p.startsWith("/dev/cu.") || p.startsWith("/dev/tty.")),
          ports
        )
        assertEquals(ports, ports.sorted)
      case Left(error) => fail(s"listing failed: $error")
    }
  }

  test("Windows on a 32-bit architecture is Unsupported, with the reason") {
    Serial
      .makeFor[IO](() => Right(Host(Os.Windows, "x86")))
      .map(_.map(_ => ()))
      .assertEquals(
        Left(PlatformError.Unsupported("unsupported Windows architecture: x86"))
      )
  }

  test(
    "Windows asked for on a machine that is not Windows cannot bind kernel32, and says so"
  ) {
    assume(
      !Option(System.getProperty("os.name")).exists(_.startsWith("Windows")),
      "this machine is Windows"
    )
    Serial
      .makeFor[IO](() => Right(Host(Os.Windows, "amd64")))
      .map {
        case Left(PlatformError.Unsupported(reason)) =>
          assert(reason.startsWith("cannot load kernel32"), reason)
        case other => fail(s"expected Unsupported, got ${other.map(_ => ())}")
      }
  }

  test(
    "an operating system the library does not know is Unsupported, with the reason"
  ) {
    Serial
      .makeFor[IO](() => Os.forSystem("FreeBSD").map(Host(_, "amd64")))
      .map(_.map(_ => ()))
      .assertEquals(
        Left(PlatformError.Unsupported("unsupported operating system: FreeBSD"))
      )
  }

  test("the architecture it is told is the one it uses, not this machine's") {
    // a Linux host on an architecture the library does not know, whatever this machine is
    Serial
      .makeFor[IO](() => Right(Host(Os.Linux, "riscv64")))
      .map(_.map(_ => ()))
      .assertEquals(
        Left(
          PlatformError.Unsupported("unsupported Linux architecture: riscv64")
        )
      )
  }

  test(
    "openStream gives the port, reads from it, and releases it when the stream ends"
  ) {
    PseudoTerminal
      .resource(posix)
      .use { pty =>
        serial.flatMap { s =>
          for {
            // opening flushes pending input, so the master writes after the port is open
            read <- s
              .openStream(pty.slavePath)
              .flatMap(port =>
                Stream.exec(
                  Bytes.write(posix, pty.master, "hey".getBytes).void
                ) ++
                  port.reads.take(3)
              )
              .compile
              .toList
            again <- s
              .open(pty.slavePath)
              .use(opened => IO.pure(opened.isRight))
          } yield (new String(read.toArray), again)
        }
      }
      .assertEquals(("hey", true))
  }

  test(
    "openStream fails with the scoped error when the port cannot be opened"
  ) {
    serial
      .flatMap(
        _.openStream("/dev/does-not-exist-fs2-serial").compile.drain.attempt
      )
      .assertEquals(
        Left(
          SerialException
            .Open(OpenError.NoSuchPort("/dev/does-not-exist-fs2-serial"))
        )
      )
  }
}
