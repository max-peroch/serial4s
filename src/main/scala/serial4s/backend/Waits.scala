package serial4s.backend

import cats.MonadError
import cats.effect.Outcome
import cats.syntax.all.*

/** What every backend does the same way when it waits on a fiber. */
private[serial4s] object Waits {

  /** What to do once a cancelled wait's fiber has stopped: nothing, unless it
    * failed. A defect must not be lost because the caller had given up.
    */
  def settled[F[_], A](outcome: Outcome[F, Throwable, A])(using
      F: MonadError[F, Throwable]
  ): F[Unit] =
    outcome.fold(F.unit, error => F.raiseError[Unit](error), _.void)
}
