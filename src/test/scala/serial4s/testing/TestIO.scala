package serial4s.testing

import serial4s.errors.Errno
import cats.effect.IO

/** Test helpers over `IO[Either[Errno, A]]`. */
private[serial4s] object TestIO {

  extension [A](call: IO[Either[Errno, A]]) {

    /** The result, or a failed test if the call reported an `Errno`. */
    def ok: IO[A] =
      call.flatMap(result =>
        IO.fromEither(
          result.left.map(errno =>
            new AssertionError(s"system call failed: $errno")
          )
        )
      )
  }
}
