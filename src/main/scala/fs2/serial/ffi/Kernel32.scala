package fs2.serial.ffi

import fs2.serial.errors.{WindowsError, WindowsErrorName}
import fs2.serial.platform.NativeUnavailable
import fs2.serial.settings.SerialSettings
import fs2.serial.win32.{
  Awaited,
  CommTimeouts,
  Constants,
  Dcb,
  DcbFields,
  Event,
  Handle,
  Started,
  WindowsErrors
}
import cats.syntax.all.*

import java.lang.foreign.{
  Arena,
  FunctionDescriptor,
  Linker,
  MemoryLayout,
  MemorySegment,
  SymbolLookup,
  ValueLayout
}
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import scala.jdk.OptionConverters.*
import scala.util.Using

/** `kernel32`, through the Java Foreign Function & Memory API: plain blocking
  * calls that run when they are invoked. This is the impure edge on Windows;
  * [[WindowsLib]] suspends it into `F`.
  *
  * FFM's default lookup on Windows is only the C runtime, so `kernel32` is
  * loaded by name. `GetLastError` is saved by the linker right after each call
  * (`captureCallState`), as `errno` is on POSIX. A `HANDLE` is passed as a
  * `long`, which matches its pointer size on the two 64-bit targets.
  *
  * Nothing here has run on Windows yet. It compiles everywhere, and loading it
  * anywhere else fails with a value, never an exception.
  */
private[serial] final class Kernel32 private (
    private[ffi] val h: Kernel32.Handles,
    private[ffi] val errorOffset: Long
) {

  private[ffi] def lastError(state: MemorySegment): WindowsError =
    WindowsErrors.named(state.get(ValueLayout.JAVA_INT, errorOffset))

  /** A call that returns a `BOOL`: zero is failure. */
  private def bool(invoke: MemorySegment => Int): Either[WindowsError, Unit] = {
    val state  = Kernel32.captureState.get()
    val result = invoke(state)
    if (result == 0) Left(lastError(state)) else Right(())
  }

  // ---- the device --------------------------------------------------------

  def createFile(path: String): Either[WindowsError, Handle] =
    Using.resource(Arena.ofConfined()) { arena =>
      val name: MemorySegment =
        arena.allocateFrom(path, StandardCharsets.UTF_16LE)
      val security: MemorySegment = MemorySegment.NULL
      val access: Int  = Constants.GenericRead | Constants.GenericWrite
      val share: Int   = 0 // exclusive: this is the lock
      val state        = Kernel32.captureState.get()
      val result: Long = h.createFile.invokeExact(
        state,
        name,
        access,
        share,
        security,
        Constants.OpenExisting,
        Constants.FileFlagOverlapped,
        0L
      ): Long
      if (result == Constants.InvalidHandle) Left(lastError(state))
      else Right(Handle(result))
    }

  def closeHandle(handle: Long): Either[WindowsError, Unit] =
    bool(state => h.closeHandle.invokeExact(state, handle): Int)

  private def readDcb(dcb: MemorySegment): DcbFields =
    DcbFields(
      baudRate = dcb.get(ValueLayout.JAVA_INT, Dcb.BaudRateOffset.toLong),
      flags = dcb.get(ValueLayout.JAVA_INT, Dcb.FlagsOffset.toLong),
      byteSize =
        dcb.get(ValueLayout.JAVA_BYTE, Dcb.ByteSizeOffset.toLong) & 0xff,
      parity = dcb.get(ValueLayout.JAVA_BYTE, Dcb.ParityOffset.toLong) & 0xff,
      stopBits =
        dcb.get(ValueLayout.JAVA_BYTE, Dcb.StopBitsOffset.toLong) & 0xff,
      xonChar = dcb.get(ValueLayout.JAVA_BYTE, Dcb.XonCharOffset.toLong) & 0xff,
      xoffChar =
        dcb.get(ValueLayout.JAVA_BYTE, Dcb.XoffCharOffset.toLong) & 0xff
    )

  private def writeDcb(dcb: MemorySegment, fields: DcbFields): Unit = {
    dcb.set(ValueLayout.JAVA_INT, Dcb.BaudRateOffset.toLong, fields.baudRate)
    dcb.set(ValueLayout.JAVA_INT, Dcb.FlagsOffset.toLong, fields.flags)
    dcb.set(
      ValueLayout.JAVA_BYTE,
      Dcb.ByteSizeOffset.toLong,
      fields.byteSize.toByte
    )
    dcb.set(
      ValueLayout.JAVA_BYTE,
      Dcb.ParityOffset.toLong,
      fields.parity.toByte
    )
    dcb.set(
      ValueLayout.JAVA_BYTE,
      Dcb.StopBitsOffset.toLong,
      fields.stopBits.toByte
    )
    dcb.set(
      ValueLayout.JAVA_BYTE,
      Dcb.XonCharOffset.toLong,
      fields.xonChar.toByte
    )
    dcb.set(
      ValueLayout.JAVA_BYTE,
      Dcb.XoffCharOffset.toLong,
      fields.xoffChar.toByte
    )
  }

  /** `GetCommState` into a fresh `DCB` whose length field is set. */
  private def getDcb(
      handle: Handle,
      arena: Arena
  ): Either[WindowsError, MemorySegment] = {
    val dcb: MemorySegment = arena.allocate(Dcb.Size.toLong)
    dcb.set(ValueLayout.JAVA_INT, Dcb.LengthOffset.toLong, Dcb.Size)
    bool(state => h.getCommState.invokeExact(state, handle.value, dcb): Int)
      .map(_ => dcb)
  }

  def configure(
      handle: Handle,
      settings: SerialSettings
  ): Either[WindowsError, Unit] =
    Using.resource(Arena.ofConfined()) { arena =>
      val timeouts: MemorySegment = arena.allocate(CommTimeouts.Size.toLong)
      CommTimeouts.values.zipWithIndex.foreach { case (value, index) =>
        timeouts.set(ValueLayout.JAVA_INT, index * 4L, value)
      }
      for {
        dcb <- getDcb(handle, arena)
        _ = writeDcb(dcb, Dcb.merge(settings, readDcb(dcb)))
        _ <- bool(state =>
          h.setCommState.invokeExact(state, handle.value, dcb): Int
        )
        _ <- bool(state =>
          h.setCommTimeouts.invokeExact(state, handle.value, timeouts): Int
        )
        _ <- bool(state =>
          h.purgeComm.invokeExact(state, handle.value, Constants.PurgeAll): Int
        )
      } yield ()
    }

  def readSettings(handle: Handle): Either[WindowsError, SerialSettings] =
    Using.resource(Arena.ofConfined()) { arena =>
      getDcb(handle, arena).flatMap(dcb => Dcb.decode(readDcb(dcb)))
    }

  // ---- events and transfers ----------------------------------------------

  def createEvent(): Either[WindowsError, Event] = {
    val attributes: MemorySegment = MemorySegment.NULL
    val name: MemorySegment       = MemorySegment.NULL
    val state                     = Kernel32.captureState.get()
    val result: Long              =
      h.createEvent.invokeExact(
        state,
        attributes,
        1,
        0,
        name
      ): Long // manual-reset, not signalled
    if (result == 0L) Left(lastError(state)) else Right(Event(result))
  }

  def setEvent(event: Event): Either[WindowsError, Unit] =
    bool(state => h.setEvent.invokeExact(state, event.value): Int)

  def resetEvent(event: Event): Either[WindowsError, Unit] =
    bool(state => h.resetEvent.invokeExact(state, event.value): Int)

  /** A transfer with its own event; `Left` if the event cannot be made. */
  def operation(size: Int): Either[WindowsError, KernelOperation] =
    createEvent().map { event =>
      val arena = Arena.ofShared()
      new KernelOperation(
        this,
        arena,
        arena.allocate(size.toLong),
        arena.allocate(Constants.OverlappedSize.toLong),
        arena.allocate(4L),
        arena.allocate(16L),
        event
      )
    }

  // ---- listing -----------------------------------------------------------

  /** `QueryDosDeviceW(NULL, ...)`: the names of all DOS devices, in the
    * double-NUL-terminated form. The buffer doubles until it is big enough.
    */
  def dosDevices(): Either[WindowsError, String] = {
    @scala.annotation.tailrec
    def attempt(chars: Int): Either[WindowsError, String] = {
      val outcome = Using.resource(Arena.ofConfined()) { arena =>
        val buffer: MemorySegment = arena.allocate(chars * 2L)
        val none: MemorySegment   = MemorySegment.NULL
        val state                 = Kernel32.captureState.get()
        val written: Int          =
          h.queryDosDevice.invokeExact(state, none, buffer, chars): Int
        if (written > 0)
          Right(
            new String(
              buffer.asSlice(0L, written * 2L).toArray(ValueLayout.JAVA_BYTE),
              StandardCharsets.UTF_16LE
            )
          )
        else Left(lastError(state))
      }
      outcome match {
        case Left(error)
            if error.name == WindowsErrorName.ERROR_INSUFFICIENT_BUFFER &&
              chars < (1 << 20) =>
          attempt(chars * 2)
        case other => other
      }
    }
    attempt(4096)
  }
}

/** One overlapped transfer: its memory, its `OVERLAPPED`, and its event. */
private[ffi] final class KernelOperation(
    k: Kernel32,
    arena: Arena,
    buffer: MemorySegment,
    overlapped: MemorySegment,
    count: MemorySegment,
    waitArray: MemorySegment,
    event: Event
) {

  val size: Int = buffer.byteSize().toInt

  private val none: MemorySegment = MemorySegment.NULL

  def fill(bytes: Array[Byte], from: Int, n: Int): Unit = {
    val offset = Math.max(0, Math.min(from, bytes.length))
    val taken  = Math.max(0, Math.min(n, Math.min(size, bytes.length - offset)))
    MemorySegment.copy(bytes, offset, buffer, ValueLayout.JAVA_BYTE, 0L, taken)
  }

  def bytes(n: Int): Array[Byte] = {
    val taken = Math.max(0, Math.min(n, size))
    val out   = new Array[Byte](taken)
    MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0L, out, 0, taken)
    out
  }

  /** Clears the `OVERLAPPED` and points it at this operation's event. */
  private def arm(): Unit = {
    overlapped.fill(0.toByte)
    overlapped.set(
      ValueLayout.JAVA_LONG,
      Constants.OverlappedEventOffset.toLong,
      event.value
    )
    k.resetEvent(event)
    ()
  }

  private def start(
      handle: Handle,
      n: Int,
      reading: Boolean
  ): Either[WindowsError, Started] =
    if (n <= 0 || n > size)
      Left(WindowsErrors.of(WindowsErrorName.ERROR_INVALID_PARAMETER))
    else {
      arm()
      val state       = Kernel32.captureState.get()
      val result: Int =
        if (reading)
          k.h.readFile.invokeExact(
            state,
            handle.value,
            buffer,
            n,
            none,
            overlapped
          ): Int
        else
          k.h.writeFile.invokeExact(
            state,
            handle.value,
            buffer,
            n,
            none,
            overlapped
          ): Int
      if (result != 0) Right(Started.Done)
      else {
        val error = k.lastError(state)
        if (error.name == WindowsErrorName.ERROR_IO_PENDING)
          Right(Started.Pending)
        else Left(error)
      }
    }

  def startRead(handle: Handle, n: Int): Either[WindowsError, Started] =
    start(handle, n, reading = true)

  def startWrite(handle: Handle, n: Int): Either[WindowsError, Started] =
    start(handle, n, reading = false)

  /** Waits for this transfer's event or `wake`: the lower index wins, so a
    * finished transfer is preferred over a wake.
    */
  def await(wake: Event): Either[WindowsError, Awaited] = {
    waitArray.set(ValueLayout.JAVA_LONG, 0L, event.value)
    waitArray.set(ValueLayout.JAVA_LONG, 8L, wake.value)
    val state       = Kernel32.captureState.get()
    val result: Int =
      k.h.waitForMultipleObjects.invokeExact(
        state,
        2,
        waitArray,
        0,
        Constants.Infinite
      ): Int
    result match {
      case Constants.WaitObject0               => Right(Awaited.Finished)
      case i if i == Constants.WaitObject0 + 1 => Right(Awaited.Woken)
      case Constants.WaitFailed                => Left(k.lastError(state))
      case _ => Left(WindowsErrors.of(WindowsErrorName.ERROR_INVALID_PARAMETER))
    }
  }

  private def result(
      handle: Handle,
      wait: Boolean
  ): (Boolean, MemorySegment, Int) = {
    count.set(ValueLayout.JAVA_INT, 0L, 0)
    val state   = Kernel32.captureState.get()
    val ok: Int =
      k.h.getOverlappedResult.invokeExact(
        state,
        handle.value,
        overlapped,
        count,
        if (wait) 1 else 0
      ): Int
    (ok != 0, state, count.get(ValueLayout.JAVA_INT, 0L))
  }

  def complete(handle: Handle): Either[WindowsError, Int] = {
    val (ok, state, moved) = result(handle, wait = false)
    if (ok) Right(moved) else Left(k.lastError(state))
  }

  /** `CancelIoEx`, then wait for the transfer to stop. `ERROR_NOT_FOUND` from
    * the cancel means it had already finished; `ERROR_OPERATION_ABORTED` from
    * the result means it was cancelled, and the bytes it had moved still count.
    */
  def cancel(handle: Handle): Either[WindowsError, Int] = {
    val state          = Kernel32.captureState.get()
    val cancelled: Int =
      k.h.cancelIoEx.invokeExact(state, handle.value, overlapped): Int
    val cancelError =
      if (cancelled != 0) None
      else
        Some(k.lastError(state))
          .filter(_.name != WindowsErrorName.ERROR_NOT_FOUND)
    cancelError.toLeft(()).flatMap { _ =>
      val (ok, resultState, moved) = result(handle, wait = true)
      if (ok) Right(moved)
      else {
        val error = k.lastError(resultState)
        if (error.name == WindowsErrorName.ERROR_OPERATION_ABORTED) Right(moved)
        else Left(error)
      }
    }
  }

  def close(): Unit = {
    k.closeHandle(event.value)
    arena.close()
  }
}

private[serial] object Kernel32 {

  /** Where the linker saves `GetLastError`: one segment per thread. */
  private[ffi] val captureState: ThreadLocal[MemorySegment] =
    ThreadLocal.withInitial(() =>
      Arena.ofAuto().allocate(Linker.Option.captureStateLayout())
    )

  private[ffi] final case class Handles(
      createFile: MethodHandle,
      closeHandle: MethodHandle,
      getCommState: MethodHandle,
      setCommState: MethodHandle,
      setCommTimeouts: MethodHandle,
      purgeComm: MethodHandle,
      createEvent: MethodHandle,
      setEvent: MethodHandle,
      resetEvent: MethodHandle,
      readFile: MethodHandle,
      writeFile: MethodHandle,
      getOverlappedResult: MethodHandle,
      cancelIoEx: MethodHandle,
      waitForMultipleObjects: MethodHandle,
      queryDosDevice: MethodHandle
  )

  /** `kernel32` of this machine, or why it cannot be used. On a machine that is
    * not Windows the library is not found and this is a `Left`.
    */
  def load(): Either[NativeUnavailable, Kernel32] =
    Either
      .catchNonFatal {
        val lookup = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val linker = Linker.nativeLinker()
        val offset = Linker.Option
          .captureStateLayout()
          .byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))
        bind(linker, lookup).map(new Kernel32(_, offset))
      }
      .leftMap(error =>
        NativeUnavailable(s"cannot load kernel32: ${error.getMessage}")
      )
      .flatten

  private def bind(
      linker: Linker,
      lookup: SymbolLookup
  ): Either[NativeUnavailable, Handles] = {
    val int     = ValueLayout.JAVA_INT
    val long    = ValueLayout.JAVA_LONG
    val address = ValueLayout.ADDRESS
    val error   = Linker.Option.captureCallState("GetLastError")

    def handle(
        name: String,
        descriptor: FunctionDescriptor
    ): Either[NativeUnavailable, MethodHandle] =
      lookup
        .find(name)
        .toScala
        .toRight(NativeUnavailable(s"kernel32 symbol not found: $name"))
        .map(symbol => linker.downcallHandle(symbol, descriptor, error))

    for {
      createFile <- handle(
        "CreateFileW",
        FunctionDescriptor.of(long, address, int, int, address, int, int, long)
      )
      closeHandle  <- handle("CloseHandle", FunctionDescriptor.of(int, long))
      getCommState <- handle(
        "GetCommState",
        FunctionDescriptor.of(int, long, address)
      )
      setCommState <- handle(
        "SetCommState",
        FunctionDescriptor.of(int, long, address)
      )
      setCommTimeouts <- handle(
        "SetCommTimeouts",
        FunctionDescriptor.of(int, long, address)
      )
      purgeComm   <- handle("PurgeComm", FunctionDescriptor.of(int, long, int))
      createEvent <- handle(
        "CreateEventW",
        FunctionDescriptor.of(long, address, int, int, address)
      )
      setEvent   <- handle("SetEvent", FunctionDescriptor.of(int, long))
      resetEvent <- handle("ResetEvent", FunctionDescriptor.of(int, long))
      readFile   <- handle(
        "ReadFile",
        FunctionDescriptor.of(int, long, address, int, address, address)
      )
      writeFile <- handle(
        "WriteFile",
        FunctionDescriptor.of(int, long, address, int, address, address)
      )
      getOverlappedResult <- handle(
        "GetOverlappedResult",
        FunctionDescriptor.of(int, long, address, address, int)
      )
      cancelIoEx <- handle(
        "CancelIoEx",
        FunctionDescriptor.of(int, long, address)
      )
      waitForMultipleObjects <- handle(
        "WaitForMultipleObjects",
        FunctionDescriptor.of(int, int, address, int, int)
      )
      queryDosDevice <- handle(
        "QueryDosDeviceW",
        FunctionDescriptor.of(int, address, address, int)
      )
    } yield Handles(
      createFile,
      closeHandle,
      getCommState,
      setCommState,
      setCommTimeouts,
      purgeComm,
      createEvent,
      setEvent,
      resetEvent,
      readFile,
      writeFile,
      getOverlappedResult,
      cancelIoEx,
      waitForMultipleObjects,
      queryDosDevice
    )
  }
}
