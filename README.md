# fs2-serial

A serial port library for Scala 3, on cats-effect and fs2. It talks to the operating
system directly through the Java Foreign Function & Memory API: no C code, no JNI, no
Akka, and no jSerialComm.

**Status: early.** macOS (x86_64) is supported and tested. Linux (x86_64 and aarch64) needs
**glibc 2.42 or newer**: that release made `speed_t` the rate itself, and an older glibc or musl
refuses it. Linux passes the whole test suite on x86_64 with glibc 2.42 (Ubuntu 25.10, in Docker),
against pseudo-terminals; **aarch64 and real hardware are untested**. What exists:

- The setting and error types: `BaudRate`, `CharSize`, `Parity`, `StopBits`, `FlowControl`,
  `SerialSettings`, and the scoped errors `OpenError`, `ReadError`, `WriteError`, `ListError`.
- Baud rates from 50 to 4,000,000. A standard rate (50 to 230400) works everywhere. Any other rate
  is set through `termios` on Linux, and through the `IOSSIOSPEED` ioctl on macOS, which only a
  driver that implements it accepts (a USB serial adapter does; a pseudo-terminal answers `ENOTTY`).
  A device that refuses a rate fails the open with `OpenError.InvalidSettings`.
- `Serial.make[F]`: binds the C library once and gives a `Serial[F]`, or a `PlatformError` if this
  machine is not supported. A `Serial` that exists can work here.
- `serial.open`: a `Resource` that opens a port, locked exclusively (or `serial.openStream`: the
  same as a stream of one port, released when the stream ends), and gives a
  `SerialPort[F]` with `read` and `write`, a pull-based `reads` stream, a `writes` pipe and a `duplex`
  pipe (the same names as fs2-io's `Socket`). Cancelling a read or a write is prompt, and releasing the port is safe while one is
  pending. Works in a process with many open files (a descriptor above 1024 is fine). Tested
  against pseudo-terminals, not real hardware.
- `serial.list`: the serial devices of the machine (`/dev/cu.*` and `/dev/tty.*` on macOS; `ttyS`, `ttyUSB`, `ttyACM`, `ttyAMA` and `rfcomm` on Linux),
  by name only: it never opens a device, so a Bluetooth pairing is listed even when nothing
  answers.
- `serial.watch`: a `Stream` of `PortEvent.Appeared` and `Disappeared`, from polling the
  device directory every `PollInterval` (at least 10 ms). What is there at the start is the
  baseline and is not reported.

Not there yet: a run on aarch64 Linux and on real hardware. The macOS custom-rate ioctl has only
been exercised against a pseudo-terminal, which refuses it. A Linux pseudo-terminal forces 8 data
bits and no parity, so those two settings are not checked on a pty there.

## Planned

- Running the tests on aarch64 Linux and on a real serial adapter.
- Running the Windows backend. It is written (overlapped I/O through `kernel32`) and tested against a
  fake, but it has never run on Windows. The opt-in `WindowsPortSuite` is the first check. See
  `AGENTS.md`.

## Installing

Not released yet. Once a version is out:

```scala
libraryDependencies += "io.github.max-peroch" %% "fs2-serial" % "<version>"
```

The JVM needs `--enable-native-access=ALL-UNNAMED` (see Requirements).

## Using it

```scala
import cats.effect.IO
import fs2.serial.Serial
import fs2.serial.settings.SerialSettings

Serial.make[IO].flatMap {
  case Left(error)   => IO.println(s"not supported here: $error")
  case Right(serial) =>
    serial.open("/dev/cu.usbmodem1", SerialSettings()).use {
      case Right(port) => port.reads.take(10).compile.toList.flatMap(IO.println)
      case Left(error) => IO.println(s"cannot open: $error")
    }
}
```

The public types are in `fs2.serial` (`Serial`), `fs2.serial.port` (`SerialPort`),
`fs2.serial.settings` (what you choose), `fs2.serial.errors` (what can go wrong) and
`fs2.serial.discovery` (`PortEvent`).

## Testing on Linux

`docker/test-linux.sh` runs the whole suite on Linux with glibc 2.42, in Docker: it needs a running
Docker daemon, and the first run pulls `ubuntu:25.10` and downloads the dependencies. The script
says how to remove what it creates.

## Requirements

JDK 22 or newer. The library calls native code, so the JVM has to be told it may:

```
--enable-native-access=ALL-UNNAMED
```

Without it, JDK 25 prints a warning on the first native call, and a later JDK is expected to
refuse. For an executable jar, the manifest attribute `Enable-Native-Access: ALL-UNNAMED`
does the same.

## Inspiration

This project would not exist without two libraries it learned from:

- [akka-serial](https://github.com/jodersky/akka-serial) by Jakob Odersky: the design of a
  non-blocking port read with `select` and a self-pipe to cancel it, a reused off-heap
  buffer, an exclusive lock on open, and watching for ports that appear.
- [jSerialComm](https://github.com/Fazecast/jSerialComm) by Fazecast: the API this library
  sets out to replace, and the reference for what a serial library has to cover on every
  platform.

The code here is written from scratch. It does not copy theirs.

## License

Apache License 2.0. See [LICENSE](LICENSE).
