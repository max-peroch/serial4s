package serial4s.discovery

import serial4s.errors.{ListError, SerialException}
import serial4s.settings.PollInterval
import cats.effect.kernel.Temporal
import cats.syntax.all.*
import fs2.Stream
import serial4s.settings.PollInterval.duration

/** Turns repeated listings into the changes between them. */
private[serial4s] object PortWatcher {

  /** What changed from `before` to `after`: the ports that appeared, then the
    * ones that went.
    */
  def diff(before: Set[String], after: Set[String]): List[PortEvent] =
    (after -- before).toList.sorted.map(PortEvent.Appeared(_)) ++
      (before -- after).toList.sorted.map(PortEvent.Disappeared(_))

  /** Lists once as the baseline, then every `every`, and emits what changed. A
    * failed listing fails the stream with [[SerialException.Listing]].
    */
  def events[F[_]](
      list: F[Either[ListError, List[String]]],
      every: PollInterval
  )(using
      F: Temporal[F]
  ): Stream[F, PortEvent] = {
    val listed: F[Set[String]] =
      list.flatMap(result =>
        F.fromEither(result.bimap(SerialException.Listing(_), _.toSet))
      )
    // the first listing is the baseline: it has no previous one, so it reports nothing
    (Stream.eval(listed) ++
      Stream.awakeEvery[F](every.duration).evalMap(_ => listed)).zipWithPrevious
      .flatMap {
        case (Some(before), after) => Stream.emits(diff(before, after))
        case (None, _)             => Stream.empty
      }
  }
}
