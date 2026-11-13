# AGENTS.md

fs2-serial is a serial port library for Scala 3, on cats-effect and fs2. It replaces
jSerialComm and akka-serial: it talks to the operating system directly through the Java
Foreign Function & Memory API (`java.lang.foreign`), with no C code, no JNI and no
Akka. It targets POSIX (macOS and Linux) first. Windows is out of scope for now.

The style rules are in [`.claude/rules/`](.claude/rules): follow them. They are written
with concrete `IO`, but the library API is generic in `F[_]` (tagless final): read `IO` in
a rule as `F`. Ask for the weakest typeclass that does the job (`Sync`, `Async` where
a callback or `poll` needs it, `Concurrent` for a `Stream`); NEVER mention `IO` in `main`.
Tests use `IO` through `CatsEffectSuite`. `/scala-audit` reviews code against the rules.

## What this project is about

Explicit intent, pure functional programming, and type safety. The code says what it means
in its types, and a mistake is a compile error, not a run-time surprise.

- **Explicit intent.** A value never carries a meaning that only a comment explains. No
  sentinel (`0` for end of file, `None` for "wait for ever", `Right(())` for "try again"),
  no `String` for a kind of thing (an errno, a direction), no `Boolean` mode. Name it:
  `ReadOutcome.EndOfFile`, `WaitLimit.Unlimited`, `Wakeup.Retry`, `ErrnoName.EAGAIN`.
- **Pure functional.** An effect is a value of `F` that does nothing until run. Nothing
  runs at object initialisation or in a plain method that returns `Unit`. The only impure
  code is `ffi/Syscalls.scala`, the edge in front of the C library, and it is reached
  only through the `F`-valued `Posix[F]`. Lifecycles are `Resource`s, released in reverse
  order of acquisition, never a hand-written `close`. Tests do not mutate: use `Ref`.
- **fs2 first.** The streaming API is shaped like fs2-io's `Socket` (`read` and `write` are the
  effects; `reads`, `writes` and `duplex` are the stream and the pipes), and built from fs2:
  `concurrently` in `duplex`, `unchunks` in `reads`, `awakeEvery` and `evalMapAccumulate` in the
  watcher, `Files.forAsync` for listing. Reach for an fs2 or fs2-io construct before writing a loop.
  Two places fs2 does not fit, on purpose: the native wait that must be cancelled promptly (a blocking
  `select` needs a fiber and a wake), and errors as values (fs2's error channel is a `Throwable`, so
  the streams raise `SerialException` while `read` and `write` return the scoped error).
- **The shared layer owns the invariants.** `ManagedPort` keeps the fact that no call reaches the
  device once the port is being released, so a backend cannot get it wrong (a Windows `HANDLE` number
  is reused by the next file the process opens). A rule every backend must obey belongs in the shared
  layer, with a test over a fake `Device`, not in each backend's documentation.
- **Type safety.** Closed sets are `enum`s, so a match is checked and a misspelling does
  not compile. A value that must satisfy a rule is an opaque type with a smart constructor.

## Packages

Each package has one job. A package depends only on those listed after its arrow, never the
other way round (checked from the imports; there is no cycle).

```
fs2.serial            Serial[F], Serial.make      composition root: made once, it picks the backend
  -> settings, errors, port, backend, discovery, platform, ffi
fs2.serial.settings   BaudRate, CharSize, Parity, StopBits, FlowControl, SerialSettings,
                      SettingError, PollInterval   what a caller chooses: valid values only
fs2.serial.errors     OpenError, ReadError, WriteError, ListError, PlatformError, SerialException,
                      SystemError (Posix | Windows), Errno, ErrnoName, WindowsError, WindowsErrorName
                                                   what can go wrong, as values
fs2.serial.port       SerialPort, ManagedPort      what every OS shares: one read and one write
  -> backend, errors, settings                     at a time, closing, streams
fs2.serial.backend    Backend[F], Device[F]        the seam to an operating system
  -> errors, settings
fs2.serial.backend.posix  PosixBackend, PosixDevice, PortOpener, IoErrors
  -> posix, discovery, platform, backend, errors, settings   macOS and Linux: select, wake pipe
fs2.serial.backend.windows  WindowsBackend, WindowsDevice, WindowsOpener, WindowsIoErrors
  -> win32, backend, errors, settings   Windows: overlapped I/O, wake event
fs2.serial.win32      Win32[F], Operation[F], Dcb, CommTimeouts, WindowsErrors   the Windows calls as an algebra, plus pure data
  -> errors, settings
fs2.serial.discovery  PortEvent, PortLister, PortWatcher      finding ports
  -> platform, errors, settings
fs2.serial.posix      Posix[F], IoBuffer[F], Fd, Selection, WaitLimit, ReadOutcome   the POSIX calls as an algebra
  -> errors, settings
fs2.serial.ffi        Libc, Syscalls, SegmentBuffer   the only code that calls into C
  -> posix, platform, errors, settings
fs2.serial.platform   Platform, MacOs, Linux, Os, Host, Width, NonStandardRates, NativeUnavailable
                                                   per-OS data: no code
  -> errors, settings
```

Test helpers are in `fs2.serial.testing`. A test sits in the package of what it tests.

**`java.lang.foreign` lives only in `ffi`** (the tests' `PseudoTerminal` helper aside). `Posix`
hands out an `IoBuffer[F]` instead of a pointer, and `platform` is plain data. Keep it that way:
a new import of `java.lang.foreign` outside `ffi` is a design error.

**Known gaps.** `platform/Linux.scala` requires glibc 2.42 or newer on purpose: the project is
bleeding edge, and the older speed-code conventions are not supported. It was checked on x86_64
glibc 2.42 (Ubuntu 25.10 in Docker): a C probe confirmed every constant, and the whole suite
passes. aarch64 has not been run. On Linux, run the suite with the Docker recipe below. A Linux
pty forces 8 data bits and no parity, so `LibcSuite` expects that there. The macOS custom-rate ioctl (`IOSSIOSPEED`) has only met a
pseudo-terminal, which refuses it; it needs a USB serial adapter to be exercised.

**Running the tests on Linux.** `docker/test-linux.sh` builds `docker/Dockerfile` (`ubuntu:25.10`,
because glibc 2.42 is the oldest the Linux backend supports, and the usual JDK images are older),
runs the format checks and every test as root, then the two permission suites as a normal user
(they skip themselves as root). It mounts the project read-only and keeps the caches in Docker
volumes. The image has `gcc` for the C programs that print platform constants (rule 1); it is not
part of the build. The first run pulls the base image: never pull or run an image the maintainer
has not approved.

## Windows

Written, never run on Windows (`backend.windows`, `win32`, `ffi/Kernel32`, `ffi/WindowsLib`; 64-bit only). It is tested over a fake `Win32` and by the opt-in `WindowsPortSuite` (env `FS2_SERIAL_TEST_PORT`). The design: `Backend`/`Device` are the seam, `ManagedPort` is shared,
`SystemError.Windows` exists, and `Serial.make` already dispatches on `Os` and says "Windows has no
backend yet". Nothing in `posix`, `ffi` or `platform` fits Windows: it has handles, not descriptors,
and overlapped I/O, not `select`. A Windows backend is a new `backend.windows` plus its own calls
(`CreateFileW`, `Get/SetCommState`, `SetCommTimeouts`, overlapped `ReadFile`/`WriteFile`,
`WaitForMultipleObjects`, `CancelIoEx`, `PurgeComm`, `ClearCommError`, `CloseHandle`, and
`QueryDosDevice` or the `SERIALCOMM` registry key for listing), loaded from `kernel32` explicitly
(FFM's default lookup is the C runtime only). Decisions already made: `ERROR_ACCESS_DENIED` on open
means `PortBusy` (a COM port has no per-user permissions); open `\\.\COMn`, always, for ports above 9;
`WindowsErrorName` has names only and takes its numbers from `winerror.h` with the backend.

I cannot run anything on Windows: the maintainer runs the Windows tests and pastes the output. A test that opens a real COM port is opt-in (`FS2_SERIAL_TEST_PORT` names the port, and the test is skipped when it is absent). Tell the maintainer before it opens the port (rule 11), send only harmless commands (`AT`, `ATI`, `ATE0`, `ATE1`), and NEVER dial (`ATD`), answer (`ATA`) or store a profile (`AT&W`). What still cannot be checked: hot unplug (unless the device is USB), and two ends of a link. Struct sizes and constants (`DCB`,
`COMMTIMEOUTS`, `OVERLAPPED`) can also be printed with a `mingw-w64` probe in Docker; installing mingw
or Wine in the image needs the maintainer's approval, like any other image. Do not claim a Windows
backend works until it has run on Windows.

## Rules

1. **OS behaviour.** ALWAYS check termios, `select`, `fcntl` and `flock` behaviour
   against the man pages or POSIX text, and check constants and struct layouts
   against the platform headers (macOS and Linux differ): print them with a small C
   program built against the SDK, as `platform/MacOs.scala` documents. NEVER write them from
   memory, and never fix a mapping on a guess: run the call and look at the `errno`. Cite the page in the doc comment (`tcsetattr(3)`). If you cannot read the
   part that matters, say so.
2. **One responsibility per package.** See "Packages" below. The public API is pure Scala
   on cats-effect and fs2. `Syscalls` makes the calls, `Libc[F]` suspends them behind
   `Posix[F]`, and everything above uses only `Posix[F]`, so it is tested with a pure
   fake. Dependencies point inward only; a new import that closes a cycle is a design
   error: move the type instead. `java.lang.foreign` belongs in `ffi`: see "Known
   leaks" below for where it still is not.
3. **Types.** NEVER use `throw`, `null`, `return`, `???` or a Boolean mode flag.
   NEVER add a `var` outside a marked hot loop. Failures are values (`Either`, an enum
   of errors). Make invalid values unrepresentable: opaque types, enums and smart
   constructors (`BaudRate`, `CharSize`, `Parity`, `StopBits`).
   **Scoped errors.** One error enum per operation, never one `SerialError` for all:
   `OpenError` (`NoSuchPort`, `AccessDenied`, `PortBusy`, `InvalidSettings`, `Io`),
   `ReadError` and `WriteError` (`Disconnected`, `Closed`, `Io`), `ListError`
   (`NoSuchDirectory`, `AccessDenied`, `Unreadable`, `Unsupported`). A
   signature says what can fail. Return them in `Either` (`F[Either[OpenError, A]]`,
   `EitherT` for a long chain), and fail a stream with its scoped type
   (`SerialException`). A case that comes from the OS carries an `Errno`: its number and an
   `ErrnoName` enum case (`EACCES`), never a `String`. Only the `port` package maps
   `errno` to these types, in one place. A state that means a bug (`EBADF`,
   `EFAULT`) is a defect that fails the effect: do not widen the enums for it.
4. **Native resources.** Every file descriptor, pipe end and `Arena` is acquired in a
   `Resource` and released on cancel and on error, in reverse order of acquisition. NEVER
   close a descriptor while a read or write is using it: the last thing acquired is the
   finalizer that stops them (wake-up pipe, flag, locks), so it runs first. Check every
   return value and `errno`.
5. **Reads and writes.** Reads block in `select` on the port and a wake-up pipe (not `poll`: macOS's man page says
   it "does not support devices"), on the
   blocking pool, with no timer polling. A stream only reads when downstream pulls.
   Writes loop until all bytes are written and wait until `select` says the port is
   writable on `EAGAIN`. `ReadOutcome.EndOfFile` is a disconnect: fail the stream, never spin.
6. **Tests.** Every change ships with tests. ALWAYS plant the bug the test is for,
   see it fail, and restore the file: a green first run proves little. A mutation that
   leaves a value unused fails to compile (`-Werror`) and proves nothing, so keep the
   value in use. Use a pseudo-terminal pair (`openpty` or `posix_openpt`) so tests run
   without hardware. Test time with `TestControl` where the code has no real I/O. NEVER
   sleep for a fixed time to wait for a port or a byte. Test cancellation and
   release, not only the happy path.
7. **Build.** Run `sbt` from the repository root only, and put settings in
   `build.sbt`. Use `-Wunused:all -Werror`. The JDK is 22 or newer (FFM is final).
   Forked tests set `--enable-native-access=ALL-UNNAMED`, and a run with
   `--illegal-native-access=deny` must pass. NEVER add sbt-jni or any C build step.
   scalafmt runs on compile and re-wraps lines: read a file again before you edit it twice.
   A test run that times out can leave the sbt server spinning: check `ps` and restart
   sbt before you measure anything.
8. **Performance.** ALWAYS measure. Run `uptime` first, and compare allocation and CPU
   time, not latency, on a busy machine. Reuse one off-heap read buffer per port; do
   not allocate per read. Say what you did not measure.
   The syscall path keeps one `errno` segment per thread, so a call allocates no arena or
   segment (the ~105 B left are its result objects). Measured: about 105 B and 450-500 ns per
   call, down from 304-424 B and 630 ns, on a busy machine, so trust the bytes more than the
   times. A test checks
   that threads do not share it. Reads and writes use `delay`, not `blocking`: every descriptor
   is non-blocking, and only `select` waits.
9. **Safety.** Native calls can crash the JVM: NEVER pass a pointer or size that was
   not checked, and bound every buffer. Open ports with `O_NOCTTY | O_NONBLOCK`, take
   an exclusive `flock`, and set `CLOCAL | CREAD`. Start from `tcgetattr` or a zeroed
   `termios`, never an uninitialised one.
10. **Documentation.** Change `README.md` and `CHANGELOG.md` (`[Unreleased]`) with a
    user-visible change. Document the non-obvious: why, limits, and what is not done
    (platforms, custom baud rates, flow control). State the `--enable-native-access`
    requirement. NEVER keep a running log of work in the repository: git has it.
11. **Hardware.** Tests never need a real device. NEVER open a real port such as
    `/dev/cu.*` or `/dev/ttyUSB*` in a test or a script unless the maintainer says so,
    and tell them first.
12. **Language.** Write short, plain sentences. State results with the numbers, and say
    what was not verified or not done. Name people with they/them unless their
    pronouns are known.
13. **Commits.** Commit only when asked. List the paths (`git commit -- path ...`):
    NEVER `git add -A` or `git commit -a`. A commit says what changed and why. NEVER
    touch files in `.claude/` that are staged but not part of your change.
14. **Pinned versions.** Scala stays on 3.9.0 (LTS). Change a pinned version only when
    asked, and say why.
