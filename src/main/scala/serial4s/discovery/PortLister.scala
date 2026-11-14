package serial4s.discovery

import serial4s.errors.ListError
import serial4s.platform.Platform
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import fs2.io.file.{Files, Path}

import java.io.IOException
import java.nio.file.{
  AccessDeniedException,
  DirectoryIteratorException,
  NoSuchFileException
}

/** Lists the serial devices of a machine, with fs2-io's `Files`, which the
  * caller supplies (so a test can give it a fake).
  */
private[serial4s] object PortLister {

  /** The serial devices among `names`, as paths in `directory`, in order. */
  def serialPorts(
      platform: Platform,
      directory: String,
      names: List[String]
  ): List[String] =
    names
      .filter(platform.isSerialPortName)
      .sorted
      .map(name => s"$directory/$name")

  /** The serial devices of this platform. */
  def list[F[_]](platform: Platform)(using
      F: Concurrent[F],
      files: Files[F]
  ): F[Either[ListError, List[String]]] =
    listIn(platform, platform.portDirectory)

  private[serial4s] def listIn[F[_]](platform: Platform, directory: String)(
      using
      F: Concurrent[F],
      files: Files[F]
  ): F[Either[ListError, List[String]]] =
    files
      .list(Path(directory))
      .map(_.fileName.toString)
      .compile
      .toList
      .attempt
      .flatMap {
        case Right(names) =>
          F.pure(Right(serialPorts(platform, directory, names)))
        case Left(io: IOException) => F.pure(Left(listError(directory, io)))
        case Left(wrapped: DirectoryIteratorException) =>
          F.pure(Left(listError(directory, wrapped.getCause)))
        case Left(defect) => F.raiseError(defect)
      }

  private def listError(directory: String, failure: IOException): ListError =
    failure match {
      case _: NoSuchFileException   => ListError.NoSuchDirectory(directory)
      case _: AccessDeniedException => ListError.AccessDenied(directory)
      case other                    =>
        ListError.Unreadable(
          directory,
          Option(other.getMessage).getOrElse(other.getClass.getName)
        )
    }
}
