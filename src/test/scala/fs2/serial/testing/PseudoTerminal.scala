package fs2.serial.testing

import fs2.serial.posix.{Fd, Posix}
import cats.effect.{IO, Resource}
import java.lang.foreign.{
  Arena,
  FunctionDescriptor,
  Linker,
  MemorySegment,
  ValueLayout
}
import scala.jdk.OptionConverters.*
import scala.util.Using

/** A pseudo-terminal pair from `openpty(3)`: what is written to `master` is
  * read from the port opened at `slavePath`, and the other way round. No
  * hardware needed.
  */
private[serial] object PseudoTerminal {

  final case class Pty(master: Fd, slave: Fd, slavePath: String)

  def resource(posix: Posix[IO]): Resource[IO, Pty] =
    Resource.make(IO.blocking(create()).flatMap(IO.fromEither))(pty =>
      posix.close(pty.master) *> posix.close(pty.slave).void
    )

  private def create(): Either[Throwable, Pty] = {
    val linker  = Linker.nativeLinker()
    val address = ValueLayout.ADDRESS
    linker
      .defaultLookup()
      .find("openpty")
      .toScala
      .toRight(new NoSuchElementException("openpty not found"))
      .flatMap { symbol =>
        val handle = linker.downcallHandle(
          symbol,
          FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            address,
            address,
            address,
            address,
            address
          )
        )
        Using.resource(Arena.ofConfined()) { arena =>
          val master: MemorySegment = arena.allocate(4L)
          val slave: MemorySegment  = arena.allocate(4L)
          val name: MemorySegment   = arena.allocate(256L)
          val none: MemorySegment   = MemorySegment.NULL
          val result: Int = handle.invokeExact(master, slave, name, none, none)
          Either.cond(
            result == 0,
            Pty(
              Fd(master.get(ValueLayout.JAVA_INT, 0L)),
              Fd(slave.get(ValueLayout.JAVA_INT, 0L)),
              name.getString(0L)
            ),
            new IllegalStateException(s"openpty returned $result")
          )
        }
      }
  }
}
