package serial4s

import serial4s.backend.Backend
import serial4s.backend.posix.PosixBackend
import serial4s.backend.windows.WindowsBackend
import serial4s.discovery.{PortEvent, PortWatcher}
import serial4s.errors.{ListError, OpenError, PlatformError, SerialException}
import serial4s.ffi.{Libc, WindowsLib}
import serial4s.platform.{Host, NativeUnavailable, Os, Platform}
import serial4s.port.{ManagedPort, SerialPort}
import serial4s.settings.{PollInterval, SerialSettings}
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files

/** The serial ports of this machine.
  *
  * Make one with [[Serial.make]]: it binds the C library once, and a `Serial`
  * that exists is one that can work here. Needs
  * `--enable-native-access=ALL-UNNAMED`: see the README.
  */
trait Serial[F[_]] {

  /** Opens `path` and releases it when the `Resource` closes: the port is
    * locked exclusively while open, so another process (or another `open`) gets
    * `OpenError.PortBusy`.
    *
    * What the caller can act on comes back as the `Left`, and then there is
    * nothing to release.
    */
  def open(
      path: String,
      settings: SerialSettings = SerialSettings()
  ): Resource[F, Either[OpenError, SerialPort[F]]]

  /** [[open]] as a stream of one port, released when the stream ends. A port
    * that cannot be opened fails the stream with `SerialException.Open`: use
    * [[open]] to get that error as a value instead.
    */
  def openStream(
      path: String,
      settings: SerialSettings = SerialSettings()
  ): Stream[F, SerialPort[F]]

  /** The serial devices of this machine, as paths to give to [[open]], in
    * order.
    *
    * It lists the names in the device directory and never opens a device. A
    * Bluetooth pairing or a phone shows up here even if nothing is plugged in:
    * being listed does not mean it answers.
    */
  def list: F[Either[ListError, List[String]]]

  /** The ports that appear and disappear, looked for every `every`. What is
    * there when the stream starts is the baseline and is not reported. A
    * listing that fails fails the stream with `SerialException.Listing`.
    */
  def watch(every: PollInterval = PollInterval.OneSecond): Stream[F, PortEvent]
}

object Serial {

  /** Binds the C library of this machine, or says why that cannot be done. */
  def make[F[_]](using F: Async[F]): F[Either[PlatformError, Serial[F]]] =
    makeFor[F](() => Host.detect())

  /** [[make]] for a host chosen by the caller: how the backend is picked, from
    * both the operating system and the architecture.
    */
  private[serial4s] def makeFor[F[_]](
      detect: () => Either[NativeUnavailable, Host]
  )(using F: Async[F]): F[Either[PlatformError, Serial[F]]] =
    F.delay(detect()).flatMap {
      case Left(unavailable) => unsupported(unavailable.reason)
      case Right(Host(Os.Windows, architecture)) =>
        windowsBackend[F](architecture)
      case Right(host) => posixBackend[F](host)
    }

  private def unsupported[F[_]](reason: String)(using
      F: Async[F]
  ): F[Either[PlatformError, Serial[F]]] =
    F.pure(Left(PlatformError.Unsupported(reason)))

  /** Windows: `kernel32`, bound once. Only the 64-bit targets: the structures
    * the backend lays out are the 64-bit ones.
    */
  private def windowsBackend[F[_]](architecture: String)(using
      F: Async[F]
  ): F[Either[PlatformError, Serial[F]]] =
    architecture match {
      case "amd64" | "x86_64" | "aarch64" =>
        WindowsLib.load[F].map {
          case Left(unavailable) =>
            Left(PlatformError.Unsupported(unavailable.reason))
          case Right(win32) => Right(new Live[F](new WindowsBackend[F](win32)))
        }
      case other =>
        unsupported(s"unsupported Windows architecture: $other")
    }

  /** macOS and Linux: the POSIX calls for this host, bound once. */
  private def posixBackend[F[_]](host: Host)(using
      F: Async[F]
  ): F[Either[PlatformError, Serial[F]]] =
    Platform.forHost(host) match {
      case Left(unavailable) => unsupported(unavailable.reason)
      case Right(platform)   =>
        given Files[F] = Files.forAsync[F]
        Libc.load[F](platform).map {
          case Left(unavailable) =>
            Left(PlatformError.Unsupported(unavailable.reason))
          case Right(posix) =>
            Right(new Live[F](new PosixBackend[F](posix, platform)))
        }
    }

  private final class Live[F[_]](backend: Backend[F])(using F: Async[F])
      extends Serial[F] {

    def open(
        path: String,
        settings: SerialSettings
    ): Resource[F, Either[OpenError, SerialPort[F]]] =
      ManagedPort.open[F](backend, path, settings)

    def openStream(
        path: String,
        settings: SerialSettings
    ): Stream[F, SerialPort[F]] =
      Stream
        .resource(open(path, settings))
        .evalMap(
          _.fold(
            error => F.raiseError(SerialException.Open(error)),
            F.pure
          )
        )

    def list: F[Either[ListError, List[String]]] = backend.list

    def watch(every: PollInterval): Stream[F, PortEvent] =
      PortWatcher.events(list, every)
  }
}
