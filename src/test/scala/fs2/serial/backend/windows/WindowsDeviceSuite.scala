package fs2.serial.backend.windows

import fs2.serial.backend.Device
import fs2.serial.errors.{ReadError, SystemError, WindowsErrorName, WriteError}
import fs2.serial.settings.SerialSettings
import fs2.serial.testing.{FakeWin32, Start}
import cats.effect.{IO, Resource}
import fs2.Chunk
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** The Windows device over a pretend `Win32` whose transfers the test drives.
  */
final class WindowsDeviceSuite extends CatsEffectSuite {

  override def munitIOTimeout: Duration = 10.seconds

  private def device(fake: FakeWin32): Resource[IO, Device[IO]] =
    WindowsDevice.open[IO](fake, "COM3", SerialSettings()).evalMap {
      case Right(device) => IO.pure(device)
      case Left(error)   => IO.raiseError(new AssertionError(s"open: $error"))
    }

  private def text(chunk: Chunk[Byte]): String = new String(chunk.toArray)

  private def count(log: Vector[String], entry: String): Int =
    log.count(_ == entry)

  // ---- reading ----

  test("a read that finishes at once returns its bytes") {
    FakeWin32(reads = List(Start.done("hi")))
      .flatMap(fake => device(fake).use(_.read.map(_.map(text))))
      .assertEquals(Right("hi"))
  }

  test(
    "a read that is pending waits for the transfer, then returns its bytes"
  ) {
    for {
      fake   <- FakeWin32(reads = List(Start.pending("abc")))
      result <- device(fake).use { d =>
        for {
          reading <- d.read.start
          _       <- fake.read.awaited
          _       <- fake.read.finish
          result  <- reading.joinWithNever
        } yield result.map(text)
      }
    } yield assertEquals(result, Right("abc"))
  }

  test("a read that timed out with no bytes reads again") {
    for {
      fake   <- FakeWin32(reads = List(Start.moved(0), Start.done("ok")))
      result <- device(fake).use(_.read.map(_.map(text)))
      log    <- fake.log.get
    } yield {
      assertEquals(result, Right("ok"))
      assertEquals(count(log, "read start-read count=4096"), 2)
    }
  }

  test(
    "a cancelled read keeps the bytes it had taken, and the next read returns them first"
  ) {
    for {
      fake   <- FakeWin32(reads = List(Start.pendingUntilCancelled("xyz")))
      result <- device(fake).use { d =>
        for {
          reading <- d.read.start
          _       <- fake.read.awaited
          _       <- reading.cancel
          next    <- d.read
        } yield next.map(text)
      }
      log <- fake.log.get
    } yield {
      assertEquals(result, Right("xyz"))
      // no second transfer was needed: the bytes were already there
      assertEquals(log.count(_.startsWith("read start-read")), 1)
      assert(log.contains("read cancel"), log.toString)
    }
  }

  test("a stale wake left by a cancelled read does not stop the next read") {
    for {
      fake <- FakeWin32(
        reads = List(
          Start.pendingUntilCancelled(""),
          Start.pending(""),
          Start.done("fine")
        )
      )
      result <- device(fake).use { d =>
        for {
          first <- d.read.start
          _     <- fake.read.awaited
          _     <- first.cancel
          next  <- d.read
        } yield next.map(text)
      }
      log <- fake.log.get
    } yield {
      assertEquals(result, Right("fine"))
      assert(
        log.exists(_.startsWith("reset")),
        "the stale wake was never reset"
      )
    }
  }

  test(
    "shutting down ends a pending read with Closed, after cancelling the transfer"
  ) {
    for {
      fake   <- FakeWin32(reads = List(Start.pending("")))
      result <- device(fake).use { d =>
        for {
          reading <- d.read.start
          _       <- fake.read.awaited
          _       <- d.shutdown
          result  <- reading.joinWithNever
        } yield result.map(text)
      }
      log <- fake.log.get
    } yield {
      assertEquals(result, Left(ReadError.Closed))
      assert(log.contains("read cancel"), log.toString)
    }
  }

  test("after shutdown a read never starts a transfer") {
    for {
      fake   <- FakeWin32(reads = List(Start.done("never")))
      result <- device(fake).use(d => d.shutdown *> d.read.map(_.map(text)))
      log    <- fake.log.get
    } yield {
      assertEquals(result, Left(ReadError.Closed))
      assertEquals(log.count(_.startsWith("read start-read")), 0)
    }
  }

  test("a read that fails because the device went away is Disconnected") {
    FakeWin32(reads =
      List(Start.failing(WindowsErrorName.ERROR_DEVICE_NOT_CONNECTED))
    )
      .flatMap(fake => device(fake).use(_.read.map(_.map(text))))
      .assertEquals(Left(ReadError.Disconnected))
  }

  test("a read that fails for another reason keeps the Windows error") {
    val error = Start.error(WindowsErrorName.ERROR_INVALID_PARAMETER)
    FakeWin32(reads =
      List(Start.failing(WindowsErrorName.ERROR_INVALID_PARAMETER))
    )
      .flatMap(fake => device(fake).use(_.read.map(_.map(text))))
      .assertEquals(Left(ReadError.Io(SystemError.Windows(error))))
  }

  // ---- writing ----

  test("a long write is sent in buffer-sized pieces") {
    for {
      fake <- FakeWin32(writes =
        List(Start.moved(4096), Start.moved(4096), Start.moved(1808))
      )
      result <- device(fake).use(
        _.write(Chunk.array(Array.fill[Byte](10000)(1)))
      )
      log <- fake.log.get
    } yield {
      assertEquals(result, Right(()))
      assertEquals(
        log.filter(_.startsWith("write fill")),
        Vector(
          "write fill from=0 count=4096",
          "write fill from=4096 count=4096",
          "write fill from=8192 count=1808"
        )
      )
    }
  }

  test("a write that moved fewer bytes than asked goes on with the rest") {
    for {
      fake   <- FakeWin32(writes = List(Start.moved(100), Start.moved(150)))
      result <- device(fake).use(_.write(Chunk.array(Array.fill[Byte](250)(1))))
      log    <- fake.log.get
    } yield {
      assertEquals(result, Right(()))
      assertEquals(
        log.filter(_.startsWith("write fill")),
        Vector("write fill from=0 count=250", "write fill from=100 count=150")
      )
    }
  }

  test("a write that is pending waits for the transfer") {
    for {
      fake   <- FakeWin32(writes = List(Start.pending("12345")))
      result <- device(fake).use { d =>
        for {
          writing <- d.write(Chunk.array("12345".getBytes)).start
          _       <- fake.write.awaited
          _       <- fake.write.finish
          result  <- writing.joinWithNever
        } yield result
      }
    } yield assertEquals(result, Right(()))
  }

  test("shutting down ends a pending write with Closed") {
    for {
      fake   <- FakeWin32(writes = List(Start.pending("")))
      result <- device(fake).use { d =>
        for {
          writing <- d.write(Chunk.array("abc".getBytes)).start
          _       <- fake.write.awaited
          _       <- d.shutdown
          result  <- writing.joinWithNever
        } yield result
      }
    } yield assertEquals(result, Left(WriteError.Closed))
  }

  test(
    "a cancelled write is cancelled in Windows too, and the device still works"
  ) {
    for {
      fake   <- FakeWin32(writes = List(Start.pending(""), Start.moved(3)))
      result <- device(fake).use { d =>
        for {
          writing <- d.write(Chunk.array("abc".getBytes)).start
          _       <- fake.write.awaited
          _       <- writing.cancel
          next    <- d.write(Chunk.array("xyz".getBytes))
        } yield next
      }
      log <- fake.log.get
    } yield {
      assertEquals(result, Right(()))
      assert(log.contains("write cancel"), log.toString)
    }
  }

  test("a write that fails because the device went away is Disconnected") {
    FakeWin32(writes = List(Start.failing(WindowsErrorName.ERROR_GEN_FAILURE)))
      .flatMap(fake => device(fake).use(_.write(Chunk.array("abc".getBytes))))
      .assertEquals(Left(WriteError.Disconnected))
  }

  test("after shutdown a write never starts a transfer") {
    for {
      fake   <- FakeWin32(writes = List(Start.moved(3)))
      result <- device(fake).use(d =>
        d.shutdown *> d.write(Chunk.array("abc".getBytes))
      )
      log <- fake.log.get
    } yield {
      assertEquals(result, Left(WriteError.Closed))
      assertEquals(log.count(_.startsWith("write start-write")), 0)
    }
  }
}
