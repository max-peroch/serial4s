package fs2.serial.platform

import fs2.serial.errors.ErrnoName
import fs2.serial.settings.CharSize

/** Everything that differs between operating systems, as plain data: constants,
  * struct layouts and sizes. Reading and writing the structs is `ffi`'s job;
  * this says only where things are.
  *
  * Each value comes from the platform's own headers or sources, never from
  * memory: see the implementation's comment for how it was checked.
  */
private[serial] trait Platform {

  def name: String

  /** The directory whose entries are the serial devices. */
  def portDirectory: String

  /** Whether an entry of [[portDirectory]] is a serial device: pseudo-terminals
    * and the rest are not.
    */
  def isSerialPortName(name: String): Boolean

  /** Name to number, for the errors this library maps. */
  def errnos: Map[ErrnoName, Int]

  // open(2), fcntl(2), flock(2)
  def oRdwr: Int
  def oNonblock: Int
  def oNoctty: Int
  def oCloexec: Int
  def fGetfl: Int
  def fSetfl: Int
  def fSetfd: Int
  def fdCloexec: Int
  def lockEx: Int
  def lockNb: Int

  // select(2): `tv_sec` is 8 bytes at 0
  def fdSetBytes: Int

  /** The libc symbol for `select`. The standard `fd_set` holds 1024
    * descriptors; a bigger bitmap is passed for a higher descriptor, and macOS
    * only accepts that through its extended variant.
    */
  def selectSymbol: String
  def timevalSize: Int
  def timevalUsecOffset: Int
  def timevalUsecWidth: Width

  // termios(4)
  def termiosSize: Int
  def flagWidth: Width
  def iflagOffset: Int
  def cflagOffset: Int
  def ccOffset: Int
  def vmin: Int
  def vtime: Int
  def tcsanow: Int
  def tcioflush: Int
  def csize: Long
  def cstopb: Long
  def cread: Long
  def parenb: Long
  def parodd: Long
  def clocal: Long
  def crtscts: Long
  def ixon: Long
  def ixoff: Long
  def ixany: Long
  def charSizeBits(size: CharSize): Long

  /** The size of `speed_t`. */
  def speedWidth: Width

  /** How to set a rate that is not one of `BaudRate.standard`. */
  def nonStandardRates: NonStandardRates

  final def errnoName(code: Int): ErrnoName =
    errnos
      .collectFirst { case (name, number) if number == code => name }
      .getOrElse(ErrnoName.Unknown)

  final def code(name: ErrnoName): Int = errnos.getOrElse(name, -1)
}

private[serial] object Platform {

  /** The platform this JVM runs on, if the library supports it. */
  def detect(): Either[NativeUnavailable, Platform] =
    Host.detect().flatMap(forHost)

  /** The platform for an operating system and architecture, as the JVM names
    * them (`os.name`, `os.arch`).
    */
  def forSystem(
      osName: String,
      architecture: String
  ): Either[NativeUnavailable, Platform] =
    Os.forSystem(osName).flatMap(os => forHost(Host(os, architecture)))

  /** The POSIX platform data for a host. The two Linux architectures share the
    * generic kernel definitions. Windows has none: it needs its own backend.
    */
  def forHost(host: Host): Either[NativeUnavailable, Platform] =
    host.os match {
      case Os.Mac   => Right(MacOs)
      case Os.Linux =>
        host.architecture match {
          case "amd64" | "x86_64" | "aarch64" => Right(Linux)
          case other                          =>
            Left(NativeUnavailable(s"unsupported Linux architecture: $other"))
        }
      case Os.Windows =>
        Left(
          NativeUnavailable(
            "Windows has no POSIX platform: it needs its own backend"
          )
        )
    }
}
