package fs2.serial.ffi

import fs2.serial.errors.{Errno, ErrnoName}
import fs2.serial.platform.{
  NativeUnavailable,
  NonStandardRates,
  Platform,
  Width
}
import fs2.serial.posix.{Fd, ReadOutcome, Selection, WaitLimit}
import fs2.serial.settings.{
  BaudRate,
  CharSize,
  FlowControl,
  Parity,
  SerialSettings,
  StopBits
}
import java.lang.foreign.{
  Arena,
  FunctionDescriptor,
  Linker,
  MemoryLayout,
  MemorySegment,
  ValueLayout
}
import java.lang.invoke.{MethodHandle, MethodHandles, MethodType}
import scala.annotation.tailrec
import scala.jdk.OptionConverters.*
import scala.util.Using

/** The C library, through the Java Foreign Function & Memory API: plain
  * blocking calls that run when they are invoked. This is the impure edge;
  * [[Libc]] suspends it into `F`, and is the only way the rest of the library
  * reaches it.
  *
  * `errno` is read from the state the linker captures at each call
  * (`captureCallState`): reading it afterwards would race with the JVM's own
  * calls. `open(2)` and `fcntl(2)` are variadic, so their handles say where the
  * variadic arguments start (`firstVariadicArg`): on Apple Silicon a variadic
  * argument is passed differently from a fixed one.
  */
private[serial] final class Syscalls private (
    platform: Platform,
    handles: Syscalls.Handles
) {

  private val openFlags: Int =
    platform.oRdwr | platform.oNonblock | platform.oNoctty | platform.oCloexec

  private val invalidArgument: Errno =
    Errno(platform.code(ErrnoName.EINVAL), ErrnoName.EINVAL)

  def open(path: String): Either[Errno, Fd] =
    Using.resource(Arena.ofConfined()) { arena =>
      val cPath: MemorySegment = arena.allocateFrom(path)
      val flags: Int           = openFlags
      call(state =>
        (handles.open.invokeExact(state, cPath, flags, 0): Int).toLong
      )(r => Fd(r.toInt))
    }

  def close(fd: Fd): Either[Errno, Unit] =
    call(state => (handles.close.invokeExact(state, fd.value): Int).toLong)(_ =>
      ()
    )

  def lock(fd: Fd): Either[Errno, Unit] = {
    val operation: Int = platform.lockEx | platform.lockNb
    call(state =>
      (handles.flock.invokeExact(state, fd.value, operation): Int).toLong
    )(_ => ())
  }

  def configure(fd: Fd, settings: SerialSettings): Either[Errno, Unit] =
    Using.resource(Arena.ofConfined()) { arena =>
      val termios: MemorySegment = arena.allocate(platform.termiosSize.toLong)
      for {
        _ <- tcgetattr(fd, termios)
        _ <- edit(termios, settings)
        _ <- tcflush(fd)
        _ <- tcsetattr(fd, termios)
        _ <- setNonStandardRate(fd, settings.baudRate)
      } yield ()
    }

  def readSettings(fd: Fd): Either[Errno, SerialSettings] =
    Using.resource(Arena.ofConfined()) { arena =>
      val termios: MemorySegment = arena.allocate(platform.termiosSize.toLong)
      tcgetattr(fd, termios).flatMap(_ => decode(termios))
    }

  /** A transfer of `count` bytes, bounded by the buffer on both sides: zero or
    * less is `EINVAL`, not a zero-length call (a read of zero bytes returns 0,
    * which would look like end of file) and not a huge unsigned size.
    */
  private def boundedSize(
      buffer: MemorySegment,
      count: Int
  ): Either[Errno, Long] =
    if (count <= 0 || buffer.byteSize() == 0L) Left(invalidArgument)
    else Right(Math.min(count.toLong, buffer.byteSize()))

  def read(
      fd: Fd,
      buffer: MemorySegment,
      count: Int
  ): Either[Errno, ReadOutcome] =
    boundedSize(buffer, count).flatMap { size =>
      call(state =>
        handles.read.invokeExact(state, fd.value, buffer, size): Long
      )(read =>
        if (read == 0L) ReadOutcome.EndOfFile else ReadOutcome.Data(read.toInt)
      )
    }

  def write(fd: Fd, buffer: MemorySegment, count: Int): Either[Errno, Int] =
    boundedSize(buffer, count).flatMap { size =>
      call(state =>
        handles.write.invokeExact(state, fd.value, buffer, size): Long
      )(_.toInt)
    }

  def pipe(): Either[Errno, (Fd, Fd)] =
    Using.resource(Arena.ofConfined()) { arena =>
      val ends: MemorySegment = arena.allocate(8L)
      call(state => (handles.pipe.invokeExact(state, ends): Int).toLong)(_ =>
        (
          Fd(ends.get(ValueLayout.JAVA_INT, 0L)),
          Fd(ends.get(ValueLayout.JAVA_INT, 4L))
        )
      ).flatMap { case (readEnd, writeEnd) =>
        val prepared = for {
          _ <- nonBlockingCloexec(readEnd)
          _ <- nonBlockingCloexec(writeEnd)
        } yield (readEnd, writeEnd)
        prepared.left.foreach { _ =>
          close(readEnd)
          close(writeEnd)
        }
        prepared
      }
    }

  @tailrec
  def select(
      read: List[Fd],
      write: List[Fd],
      timeout: WaitLimit
  ): Either[Errno, Selection] =
    selectOnce(read, write, timeout) match {
      case Left(errno) if errno.name == ErrnoName.EINTR =>
        select(read, write, timeout)
      case outcome => outcome
    }

  /** One `select(2)`, with fresh descriptor sets: after `EINTR` the old ones
    * are undefined.
    */
  private def selectOnce(
      read: List[Fd],
      write: List[Fd],
      timeout: WaitLimit
  ): Either[Errno, Selection] = {
    val asked = read ++ write
    if (asked.exists(_.value < 0)) {
      Left(invalidArgument)
    } else {
      Using.resource(Arena.ofConfined()) { arena =>
        val bytes                   = bitmapBytes(asked)
        val readSet: MemorySegment  = fdSet(arena, read, bytes)
        val writeSet: MemorySegment = fdSet(arena, write, bytes)
        val none: MemorySegment     = MemorySegment.NULL
        val limit: MemorySegment    = timeout match {
          case WaitLimit.Unlimited  => MemorySegment.NULL
          case WaitLimit.UpTo(wait) =>
            val timeval = arena.allocate(platform.timevalSize.toLong)
            writeTimeval(timeval, wait.toMicros)
            timeval
        }
        val nfds: Int = asked.map(_.value).maxOption.fold(0)(_ + 1)
        callOnce(state =>
          (handles.select.invokeExact(
            state,
            nfds,
            readSet,
            writeSet,
            none,
            limit
          ): Int).toLong
        )(ready =>
          // A count of 0 is a timeout. The bits are not trusted then: a kernel may clamp `nfds`
          // to its descriptor table and leave the bits of a descriptor it ignored set.
          if (ready == 0L) Selection(Nil, Nil)
          else
            Selection(
              read.filter(isSet(readSet, _)),
              write.filter(isSet(writeSet, _))
            )
        )
      }
    }
  }

  /** The size of the descriptor bitmap: the standard `fd_set`, or bigger, in
    * whole 8-byte words, if a descriptor does not fit in it.
    */
  private def bitmapBytes(fds: List[Fd]): Long =
    Math.max(
      platform.fdSetBytes.toLong,
      (fds.map(_.value).maxOption.getOrElse(0) / 64L + 1L) * 8L
    )

  private def fdSet(arena: Arena, fds: List[Fd], bytes: Long): MemorySegment = {
    val set = arena.allocate(bytes)
    fds.foreach { fd =>
      val index = (fd.value / 8).toLong
      val bit   = 1 << (fd.value % 8)
      set.set(
        ValueLayout.JAVA_BYTE,
        index,
        (set.get(ValueLayout.JAVA_BYTE, index) | bit).toByte
      )
    }
    set
  }

  private def isSet(set: MemorySegment, fd: Fd): Boolean =
    (set.get(
      ValueLayout.JAVA_BYTE,
      (fd.value / 8).toLong
    ) & (1 << (fd.value % 8))) != 0

  private def nonBlockingCloexec(fd: Fd): Either[Errno, Unit] = {
    val getfl: Int   = platform.fGetfl
    val setfl: Int   = platform.fSetfl
    val setfd: Int   = platform.fSetfd
    val cloexec: Int = platform.fdCloexec
    for {
      flags <- call(state =>
        (handles.fcntl.invokeExact(state, fd.value, getfl, 0): Int).toLong
      )(
        _.toInt
      )
      withNonBlock: Int = flags | platform.oNonblock
      _ <- call(state =>
        (handles.fcntl
          .invokeExact(state, fd.value, setfl, withNonBlock): Int).toLong
      )(_ => ())
      _ <- call(state =>
        (handles.fcntl.invokeExact(state, fd.value, setfd, cloexec): Int).toLong
      )(_ => ())
    } yield ()
  }

  private def tcgetattr(fd: Fd, termios: MemorySegment): Either[Errno, Unit] =
    call(state =>
      (handles.tcgetattr.invokeExact(state, fd.value, termios): Int).toLong
    )(_ => ())

  private def tcsetattr(fd: Fd, termios: MemorySegment): Either[Errno, Unit] = {
    val when: Int = platform.tcsanow
    call(state =>
      (handles.tcsetattr
        .invokeExact(state, fd.value, when, termios): Int).toLong
    )(_ => ())
  }

  private def tcflush(fd: Fd): Either[Errno, Unit] = {
    val queue: Int = platform.tcioflush
    call(state =>
      (handles.tcflush.invokeExact(state, fd.value, queue): Int).toLong
    )(_ => ())
  }

  /** Raw mode, then the settings. `cfmakeraw` clears echo, line editing and
    * translation.
    */
  private def edit(
      termios: MemorySegment,
      settings: SerialSettings
  ): Either[Errno, Unit] = {
    handles.cfmakeraw.invokeExact(termios): Unit

    val stopBits = settings.stopBits match {
      case StopBits.One => 0L
      case StopBits.Two => platform.cstopb
    }
    val parity = settings.parity match {
      case Parity.NoParity => 0L
      case Parity.Even     => platform.parenb
      case Parity.Odd      => platform.parenb | platform.parodd
    }
    val hardware = settings.flowControl match {
      case FlowControl.Hardware => platform.crtscts
      case _                    => 0L
    }
    val software = settings.flowControl match {
      case FlowControl.Software => platform.ixon | platform.ixoff
      case _                    => 0L
    }

    val controlMask =
      platform.csize | platform.cstopb | platform.parenb | platform.parodd |
        platform.crtscts
    val control =
      readFlag(termios, platform.cflagOffset) & ~controlMask
    writeFlag(
      termios,
      platform.cflagOffset,
      control | platform.cread | platform.clocal | platform.charSizeBits(
        settings.charSize
      ) |
        stopBits | parity | hardware
    )

    val inputMask = platform.ixon | platform.ixoff | platform.ixany
    val input     = readFlag(termios, platform.iflagOffset) & ~inputMask
    writeFlag(termios, platform.iflagOffset, input | software)

    termios.set(
      ValueLayout.JAVA_BYTE,
      (platform.ccOffset + platform.vmin).toLong,
      1.toByte
    )
    termios.set(
      ValueLayout.JAVA_BYTE,
      (platform.ccOffset + platform.vtime).toLong,
      0.toByte
    )

    setSpeed(termios, termiosSpeed(settings.baudRate))
  }

  private def readFlag(termios: MemorySegment, offset: Int): Long =
    platform.flagWidth match {
      case Width.Bits32 =>
        Integer.toUnsignedLong(termios.get(ValueLayout.JAVA_INT, offset.toLong))
      case Width.Bits64 => termios.get(ValueLayout.JAVA_LONG, offset.toLong)
    }

  private def writeFlag(
      termios: MemorySegment,
      offset: Int,
      value: Long
  ): Unit =
    platform.flagWidth match {
      case Width.Bits32 =>
        termios.set(ValueLayout.JAVA_INT, offset.toLong, value.toInt)
      case Width.Bits64 =>
        termios.set(ValueLayout.JAVA_LONG, offset.toLong, value)
    }

  private def readSpeed(termios: MemorySegment): Long =
    handles.cfgetospeed.invokeExact(termios): Long

  private def writeTimeval(timeval: MemorySegment, micros: Long): Unit = {
    timeval.set(ValueLayout.JAVA_LONG, 0L, micros / 1000000L)
    val microsPart = micros % 1000000L
    platform.timevalUsecWidth match {
      case Width.Bits32 =>
        timeval.set(
          ValueLayout.JAVA_INT,
          platform.timevalUsecOffset.toLong,
          microsPart.toInt
        )
      case Width.Bits64 =>
        timeval.set(
          ValueLayout.JAVA_LONG,
          platform.timevalUsecOffset.toLong,
          microsPart
        )
    }
  }

  /** The speed `tcsetattr` is given. A non-standard rate goes through `termios`
    * only where the platform allows it; elsewhere `tcsetattr` gets a standard
    * placeholder and the real rate is set afterwards by [[setNonStandardRate]].
    */
  private def termiosSpeed(rate: BaudRate): Int =
    platform.nonStandardRates match {
      case NonStandardRates.ThroughTermios  => rate.value
      case NonStandardRates.ThroughIoctl(_) =>
        if (rate.isStandard) rate.value else BaudRate.B38400.value
    }

  /** Sets the speed and reads it back: a library that took the call but kept
    * another speed is an error, not a success.
    */
  private def setSpeed(
      termios: MemorySegment,
      baud: Int
  ): Either[Errno, Unit] =
    for {
      _ <- call(state =>
        (handles.cfsetspeed
          .invokeExact(state, termios, baud.toLong): Int).toLong
      )(_ => ())
      _ <- Either.cond(
        readSpeed(termios) == baud.toLong,
        (),
        invalidArgument
      )
    } yield ()

  /** After `tcsetattr`, a rate that has no `B` constant and cannot go through
    * `termios` is set by the platform's ioctl, with the speed passed by
    * pointer.
    */
  private def setNonStandardRate(
      fd: Fd,
      rate: BaudRate
  ): Either[Errno, Unit] =
    platform.nonStandardRates match {
      case NonStandardRates.ThroughIoctl(request) if !rate.isStandard =>
        Using.resource(Arena.ofConfined()) { arena =>
          val speed: MemorySegment = arena.allocate(8L)
          platform.speedWidth match {
            case Width.Bits32 => speed.set(ValueLayout.JAVA_INT, 0L, rate.value)
            case Width.Bits64 =>
              speed.set(ValueLayout.JAVA_LONG, 0L, rate.value.toLong)
          }
          call(state =>
            (handles.ioctl
              .invokeExact(state, fd.value, request, speed): Int).toLong
          )(_ => ())
        }
      case _ => Right(())
    }

  private def decode(termios: MemorySegment): Either[Errno, SerialSettings] = {
    val control = readFlag(termios, platform.cflagOffset)
    val input   = readFlag(termios, platform.iflagOffset)
    val size    = control & platform.csize
    val speed   = readSpeed(termios)
    for {
      rate     <- Either.cond(speed.isValidInt, speed.toInt, invalidArgument)
      baud     <- BaudRate(rate).left.map(_ => invalidArgument)
      charSize <- CharSize.values
        .find(platform.charSizeBits(_) == size)
        .toRight(invalidArgument)
    } yield SerialSettings(
      baudRate = baud,
      charSize = charSize,
      parity =
        if ((control & platform.parenb) == 0L) Parity.NoParity
        else if ((control & platform.parodd) != 0L) Parity.Odd
        else Parity.Even,
      stopBits =
        if ((control & platform.cstopb) != 0L) StopBits.Two else StopBits.One,
      flowControl =
        if ((control & platform.crtscts) == platform.crtscts)
          FlowControl.Hardware
        else if ((input & (platform.ixon | platform.ixoff)) != 0L)
          FlowControl.Software
        else FlowControl.Off
    )
  }

  /** A call that fails with -1 and `errno`. Retried while it is interrupted by
    * a signal.
    */
  @tailrec
  private def call[A](
      invoke: MemorySegment => Long
  )(ok: Long => A): Either[Errno, A] =
    callOnce(invoke)(ok) match {
      case Left(errno) if errno.name == ErrnoName.EINTR => call(invoke)(ok)
      case outcome                                      => outcome
    }

  private def callOnce[A](
      invoke: MemorySegment => Long
  )(ok: Long => A): Either[Errno, A] =
    val state  = Syscalls.captureState.get()
    val result = invoke(state)
    if (result == -1L) {
      val code = state.get(ValueLayout.JAVA_INT, Syscalls.errnoOffset)
      Left(Errno(code, platform.errnoName(code)))
    } else {
      Right(ok(result))
    }
}

private[serial] object Syscalls {

  /** The libc of this machine, or why it cannot be used. */
  def load(platform: Platform): Either[NativeUnavailable, Syscalls] =
    Handles.bind(platform).map(new Syscalls(platform, _))

  /** Where the linker saves `errno` after a call. One segment per thread, made
    * once: a call is not given a fresh one. A call only reads it right after
    * its own call returns, on the same thread, so threads never share it.
    */
  private val captureState: ThreadLocal[MemorySegment] =
    ThreadLocal.withInitial(() => Arena.ofAuto().allocate(captureLayout))

  private val captureLayout: MemoryLayout =
    Linker.Option.captureStateLayout()

  private val errnoOffset: Long =
    Linker.Option
      .captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("errno"))

  private final case class Handles(
      open: MethodHandle,
      close: MethodHandle,
      read: MethodHandle,
      write: MethodHandle,
      select: MethodHandle,
      pipe: MethodHandle,
      fcntl: MethodHandle,
      flock: MethodHandle,
      tcgetattr: MethodHandle,
      tcsetattr: MethodHandle,
      tcflush: MethodHandle,
      cfmakeraw: MethodHandle,
      cfsetspeed: MethodHandle,
      cfgetospeed: MethodHandle,
      ioctl: MethodHandle
  )

  private object Handles {

    private val int     = ValueLayout.JAVA_INT
    private val long    = ValueLayout.JAVA_LONG
    private val address = ValueLayout.ADDRESS

    def bind(platform: Platform): Either[NativeUnavailable, Handles] = {
      val linker                   = Linker.nativeLinker()
      val errno                    = Linker.Option.captureCallState("errno")
      val speedLayout: ValueLayout = platform.speedWidth match {
        case Width.Bits32 => int
        case Width.Bits64 => long
      }

      /** Calls take a `long` speed whatever the size of `speed_t`. */
      def adapt(handle: MethodHandle, wanted: MethodType): MethodHandle =
        MethodHandles.explicitCastArguments(handle, wanted)

      def handle(
          name: String,
          descriptor: FunctionDescriptor,
          options: Linker.Option*
      ): Either[NativeUnavailable, MethodHandle] =
        linker
          .defaultLookup()
          .find(name)
          .toScala
          .toRight(NativeUnavailable(s"libc symbol not found: $name"))
          .map(symbol => linker.downcallHandle(symbol, descriptor, options*))

      for {
        open <- handle(
          "open",
          FunctionDescriptor.of(int, address, int, int),
          errno,
          Linker.Option.firstVariadicArg(2)
        )
        close <- handle("close", FunctionDescriptor.of(int, int), errno)
        read  <- handle(
          "read",
          FunctionDescriptor.of(long, int, address, long),
          errno
        )
        write <- handle(
          "write",
          FunctionDescriptor.of(long, int, address, long),
          errno
        )
        select <- handle(
          platform.selectSymbol,
          FunctionDescriptor.of(int, int, address, address, address, address),
          errno
        )
        pipe  <- handle("pipe", FunctionDescriptor.of(int, address), errno)
        fcntl <- handle(
          "fcntl",
          FunctionDescriptor.of(int, int, int, int),
          errno,
          Linker.Option.firstVariadicArg(2)
        )
        flock <- handle("flock", FunctionDescriptor.of(int, int, int), errno)
        tcgetattr <- handle(
          "tcgetattr",
          FunctionDescriptor.of(int, int, address),
          errno
        )
        tcsetattr <- handle(
          "tcsetattr",
          FunctionDescriptor.of(int, int, int, address),
          errno
        )
        tcflush <- handle(
          "tcflush",
          FunctionDescriptor.of(int, int, int),
          errno
        )
        cfmakeraw  <- handle("cfmakeraw", FunctionDescriptor.ofVoid(address))
        cfsetspeed <- handle(
          "cfsetspeed",
          FunctionDescriptor.of(int, address, speedLayout),
          errno
        ).map(
          adapt(
            _,
            MethodType.methodType(
              classOf[Int],
              classOf[MemorySegment],
              classOf[MemorySegment],
              classOf[Long]
            )
          )
        )
        cfgetospeed <- handle(
          "cfgetospeed",
          FunctionDescriptor.of(speedLayout, address)
        ).map(
          adapt(
            _,
            MethodType.methodType(classOf[Long], classOf[MemorySegment])
          )
        )
        ioctl <- handle(
          "ioctl",
          FunctionDescriptor.of(int, int, long, address),
          errno,
          Linker.Option.firstVariadicArg(2)
        )
      } yield Handles(
        open,
        close,
        read,
        write,
        select,
        pipe,
        fcntl,
        flock,
        tcgetattr,
        tcsetattr,
        tcflush,
        cfmakeraw,
        cfsetspeed,
        cfgetospeed,
        ioctl
      )
    }
  }
}
