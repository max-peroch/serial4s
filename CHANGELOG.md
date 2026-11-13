# Changelog

## [Unreleased]

### Added

- `Serial.make[F]`: binds the C library once and gives a `Serial[F]` (or a `PlatformError` on an
  unsupported machine). A `Serial` that exists can work on this machine.
- `serial.open`: opens a serial port as a `Resource` (`serial.openStream` as a stream of one port), locked exclusively, giving a `SerialPort[F]`
  with `read` and `write`, a pull-based `reads` stream, a `writes` pipe and a `duplex` pipe, named
  like fs2-io's `Socket`. Reads and writes are
  cancellable, and releasing the port is safe while one is pending. A path containing a NUL is
  refused. A descriptor above 1024 works: the `select` bitmap is sized to it.
- `serial.list` and `serial.watch`: the serial devices of the machine, and a stream of
  `PortEvent.Appeared` and `PortEvent.Disappeared`, polled every `PollInterval`.
- Settings: `BaudRate`, `CharSize`, `Parity`, `StopBits`, `FlowControl`, `SerialSettings`.
- Baud rates from 50 to 4,000,000. A rate that is not standard is set through `termios` on Linux
  and through the `IOSSIOSPEED` ioctl on macOS.
- Errors scoped per operation, as values: `OpenError`, `ReadError`, `WriteError`, `ListError`.
  Streams fail with `SerialException`.
- macOS (x86_64) backend, tested against pseudo-terminals.
- Linux backend (x86_64 and aarch64, glibc 2.42 or newer). The whole test suite passes on x86_64
  with glibc 2.42 (Ubuntu 25.10, in Docker), against pseudo-terminals. aarch64 is untested.
- `docker/Dockerfile` and `docker/test-linux.sh`: run the whole test suite on Linux (glibc 2.42) in
  Docker.

- The backend seam: `Backend` and `Device` separate what every operating system shares (one read and
  one write at a time, closing, streams) from how it waits. Errors carry a `SystemError`, `Posix` or
  `Windows`. `Serial.make` reports an unsupported system as a `PlatformError`.
- Windows backend (amd64 and aarch64), **written but never run on Windows**: overlapped I/O through
  `kernel32` over FFM, `\\.\COMn` paths, exclusive open (a second open is `PortBusy`), listing with
  `QueryDosDeviceW`. It is tested against a fake `Win32` (167 tests on macOS) and by an opt-in
  `WindowsPortSuite` (set `FS2_SERIAL_TEST_PORT`; sends only `AT` and `ATI`). Struct layouts come
  from the mingw-w64 headers, not from a probe. Mark/space parity and 1.5 stop bits are unsupported.

### Notes

- Needs JDK 22 or newer and `--enable-native-access=ALL-UNNAMED`.
- No C code, no JNI, no Akka: the C library is called through the Java Foreign Function and
  Memory API.
