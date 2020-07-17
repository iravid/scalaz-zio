/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio

import java.time.Instant
import scala.util.Try

import zio.duration._
import scala.annotation.tailrec
import java.util.concurrent.TimeUnit

/**
 * A `Schedule[In, Out]` defines a recurring schedule, which consumes values of type `In`, and
 * which returns values of type `Out`.
 *
 * Schedules are defined as a possibly infinite set of intervals spread out over time. Each
 * interval defines a window in which recurrance is possible.
 *
 * When schedules are used to repeat or retry effects, the starting boundary of each interval
 * produced by a schedule is used as the moment when the effect will be executed again.
 *
 * Schedules compose in the following primary ways:
 *
 *  * Union. This performs the union of the intervals of two schedules.
 *  * Intersection. This performs the intersection of the intervals of two schedules.
 *  * Sequence. This concatenates the intervals of one schedule onto another.
 *
 * In addition, schedule inputs and outputs can be transformed, filtered (to  terminate a
 * schedule early in response to some input or output), and so forth.
 *
 * A variety of other operators exist for transforming and combining schedules, and the companion
 * object for `Schedule` contains all common types of schedules, both for performing retrying, as
 * well as performing repetition.
 */
final case class Schedule2[-In, +Out](
  private val step0: Schedule2.StepFunction[In, Out]
) { self =>
  import Schedule2._
  import Schedule2.Decision._

  def ||[In1 <: In, Out2](that: Schedule2[In1, Out2]): Schedule2[In1, (Out, Out2)] =
    (self combineWith that)((l, r) => (l union r).getOrElse(l min r))

  def &&[In1 <: In, Out2](that: Schedule2[In1, Out2]): Schedule2[In1, (Out, Out2)] =
    (self combineWith that)(_ intersect _)

  /**
   * The same as `&&`, but ignores the left output.
   */
  def *>[In1 <: In, Out2](that: Schedule2[In1, Out2]): Schedule2[In1, Out2] =
    (self && that).map(_._2)

  /**
   * The same as `&&`, but ignores the right output.
   */
  def <*[In1 <: In, Out2](that: Schedule2[In1, Out2]): Schedule2[In1, Out] =
    (self && that).map(_._1)

  def >>>[Out2](that: Schedule2[Out, Out2]): Schedule2[In, Out2] = {
    def loop(self: StepFunction[In, Out], that: StepFunction[Out, Out2]): StepFunction[In, Out2] =
      (now: Instant, in: In) =>
        self(now, in) match {
          case Done(out) => that(now, out).toDone
          case Continue(out, interval, next) =>
            that(now, out) match {
              case Done(out2) => Done(out2)
              case Continue(out2, interval2, next2) =>
                val combined = (interval union interval2).getOrElse(interval min interval2)

                Continue(out2, combined, loop(next, next2))
            }
        }

    Schedule2(loop(self.step0, that.step0))
  }

  def <<<[In2](that: Schedule2[In2, In]): Schedule2[In2, Out] = that >>> self

  def ++[In1 <: In, Out2 >: Out](that: Schedule2[In1, Out2]): Schedule2[In1, Out2] = self andThen that

  def addDelay(f: Out => Duration): Schedule2[In, Out] = {
    def loop(n: Long, self: StepFunction[In, Out]): StepFunction[In, Out] =
      (now: Instant, in: In) =>
        self(now, in) match {
          case Done(out)                     => Done(out)
          case Continue(out, interval, next) => Continue(out, interval.shiftN(n)(f(out)), loop(n + 1, next))
        }

    Schedule2(loop(1, step0))
  }

  def andThen[In1 <: In, Out2 >: Out](that: Schedule2[In1, Out2]): Schedule2[In1, Out2] =
    (self andThenEither that).map(_.merge)

  def andThenEither[In1 <: In, Out2](that: Schedule2[In1, Out2]): Schedule2[In1, Either[Out, Out2]] = {
    def loop(
      self: StepFunction[In, Out],
      that: StepFunction[In1, Out2],
      onLeft: Boolean
    ): StepFunction[In1, Either[Out, Out2]] =
      (now: Instant, in: In1) =>
        if (onLeft) self(now, in) match {
          case Continue(out, interval, next) => Continue(Left(out), interval, loop(next, that, true))
          case Done(out)                     => Continue(Left(out), Interval.Nowhere, loop(next, that, false))
        }
        else
          that(now, in) match {
            case Done(r)                       => Done(Right(r))
            case Continue(out, interval, next) => Continue(Right(out), interval, loop(self, next, false))
          }

    Schedule2(loop(self.step0, that.step0, true))
  }

  def as[Out2](out2: => Out2): Schedule2[In, Out2] = self.map(_ => out2)

  def check[In1 <: In](test: (In1, Out) => Boolean): Schedule2[In1, Out] = {
    def loop(self: StepFunction[In1, Out]): StepFunction[In1, Out] =
      (now: Instant, in: In1) =>
        self(now, in) match {
          case Done(out) => Done(out)
          case Continue(out, interval, next) =>
            if (test(in, out)) Continue(out, interval, loop(next)) else Done(out)
        }

    Schedule2(loop(step0))
  }

  def collectAll: Schedule2[In, Chunk[Out]] = fold[Chunk[Out]](Chunk.empty)((xs, x) => xs :+ x)

  def combineWith[In1 <: In, Out2](
    that: Schedule2[In1, Out2]
  )(f: (Interval, Interval) => Interval): Schedule2[In1, (Out, Out2)] = {
    def loop(lprev: Option[(Interval, Out)], rprev: Option[(Interval, Out2)])(
      self: StepFunction[In1, Out],
      that: StepFunction[In1, Out2]
    ): StepFunction[In1, (Out, Out2)] = { (now: Instant, in: In1) =>
      val left  = StepFunction.stepIfNecessary(now, in, lprev, self)
      val right = StepFunction.stepIfNecessary(now, in, rprev, that)

      (left, right) match {
        case (Done(l), Done(r))           => Done(l -> r)
        case (Done(l), Continue(r, _, _)) => Done(l -> r)
        case (Continue(l, _, _), Done(r)) => Done(l -> r)
        case (Continue(l, linterval, lnext), Continue(r, rinterval, rnext)) =>
          val combined = f(linterval, rinterval)

          Continue(l -> r, combined, loop(Some((linterval, l)), Some((rinterval, r)))(lnext, rnext))
      }
    }

    Schedule2(loop(None, None)(self.step0, that.step0))
  }

  def contramap[In2](f: In2 => In): Schedule2[In2, Out] =
    Schedule2((now: Instant, in: In2) => step0(now, f(in)).contramap(f))

  /**
   * Returns a new schedule that folds over the outputs of this one.
   */
  final def fold[Z](z: Z)(f: (Z, Out) => Z): Schedule2[In, Z] = {
    def loop(z: Z, self: StepFunction[In, Out]): StepFunction[In, Z] =
      (now: Instant, in: In) =>
        self(now, in) match {
          case Done(out) => Done(f(z, out))
          case Continue(out, interval, next) =>
            val z2 = f(z, out)

            Continue(z2, interval, loop(z2, next))
        }

    Schedule2(loop(z, step0))
  }

  def map[Out2](f: Out => Out2): Schedule2[In, Out2] = Schedule2((now: Instant, in: In) => step0(now, in).map(f))

  // FIXME: Inline
  def next(now: Instant, in: In): Decision[In, Out] = {
    @tailrec
    def stepNext(now: Instant, in: In, step0: StepFunction[In, Out]): Decision[In, Out] =
      step0(now, in) match {
        case Decision.Done(out) => Decision.Done(out)
        case d @ Decision.Continue(_, interval, next) =>
          if (now.compareTo(interval.end) >= 0) stepNext(now, in, next) else d
      }

    stepNext(now, in, step0)
  }

  def repetitions: Schedule2[In, Int] =
    fold(0)((n: Int, _: Out) => n + 1)

  def unit: Schedule2[In, Unit] = self.as(())

  def untilInput[In1 <: In](f: In1 => Boolean): Schedule2[In1, Out] = check((in, _) => !f(in))

  def untilOutput(f: Out => Boolean): Schedule2[In, Out] = check((_, out) => !f(out))

  def whileInput[In1 <: In](f: In1 => Boolean): Schedule2[In1, Out] = check((in, _) => f(in))

  def whileOutput(f: Out => Boolean): Schedule2[In, Out] = check((_, out) => f(out))

  def zip[In1 <: In, Out2](that: Schedule2[In1, Out2]): Schedule2[In1, (Out, Out2)] = self zip that

  def zipWith[In1 <: In, Out2, Out3](that: Schedule2[In1, Out2])(f: (Out, Out2) => Out3): Schedule2[In1, Out3] =
    (self zip that).map(f.tupled)
}
object Schedule2 {

  /**
   * A schedule that recurs forever, collecting all inputs into a list.
   */
  def collectAll[A]: Schedule2[A, Chunk[A]] =
    identity[A].collectAll

  /**
   * A schedule that recurs as long as the condition f holds, collecting all inputs into a list.
   */
  def collectWhile[A](f: A => Boolean): Schedule2[A, Chunk[A]] =
    doWhile(f).collectAll

  /**
   * A schedule that recurs until the condition f fails, collecting all inputs into a list.
   */
  def collectUntil[A](f: A => Boolean): Schedule2[A, Chunk[A]] =
    doUntil(f).collectAll

  def delayed[In, Out](schedule: Schedule2[In, Duration]): Schedule2[In, Duration] =
    schedule.addDelay(x => x)

  /**
   * A schedule that recurs for as long as the predicate evaluates to true.
   */
  def doWhile[A](f: A => Boolean): Schedule2[A, A] =
    identity[A].whileInput(f)

  /**
   * A schedule that recurs for as long as the predicate is equal.
   */
  def doWhileEquals[A](a: => A): Schedule2[A, A] =
    identity[A].whileInput(_ == a)

  /**
   * A schedule that recurs for until the predicate evaluates to true.
   */
  def doUntil[A](f: A => Boolean): Schedule2[A, A] =
    identity[A].untilInput(f)

  /**
   * A schedule that recurs for until the predicate is equal.
   */
  def doUntilEquals[A](a: => A): Schedule2[A, A] =
    identity[A].untilInput(_ == a)

  /**
   * A schedule that recurs for until the input value becomes applicable to partial function
   * and then map that value with given function.
   * */
  def doUntil[A, B](pf: PartialFunction[A, B]): Schedule2[A, Option[B]] =
    doUntil(pf.isDefinedAt(_)).map(pf.lift(_))

  def duration(duration: Duration): Schedule2[Any, Duration] =
    Schedule2((now, _: Any) =>
      Decision.Continue(duration, Interval(now, now.plusNanos(duration.toNanos)), StepFunction.done(duration))
    )

  /**
   * A schedule that occurs everywhere, which returns the total elapsed duration since the
   * first step.
   */
  val elapsed: Schedule2[Any, Duration] = {
    def loop(start: Option[Instant]): StepFunction[Any, Duration] =
      (now: Instant, _: Any) =>
        start match {
          case None => Decision.Continue(Duration.Zero, Interval.Everywhere, loop(Some(now)))
          case Some(start) =>
            val duration = Duration(now.toEpochMilli() - start.toEpochMilli(), TimeUnit.MILLISECONDS)

            Decision.Continue(duration, Interval.Everywhere, loop(Some(start)))
        }

    Schedule2(loop(None))
  }

  /**
   * A schedule that always recurs, but will wait a certain amount between
   * repetitions, given by `base * factor.pow(n)`, where `n` is the number of
   * repetitions so far. Returns the current duration between recurrences.
   */
  def exponential(base: Duration, factor: Double = 2.0): Schedule2[Any, Duration] =
    unfoldDelay(base -> 0L) {
      case (base, i) =>
        val delay = base * math.pow(factor, i.doubleValue)

        (delay, (delay, i + 1))
    }.map(_._1)

  /**
   * A schedule that always recurs, increasing delays by summing the
   * preceding two delays (similar to the fibonacci sequence). Returns the
   * current duration between recurrences.
   */
  def fibonacci(one: Duration): Schedule2[Any, Duration] =
    unfoldDelay[(Duration, Duration)]((one, one)) {
      case (a1, a2) => (a2, (a2, a1 + a2))
    }.map(_._1)

  /**
   * A schedule that recurs once with the specified delay.
   */
  def fromDuration(duration: Duration): Schedule2[Any, Duration] =
    Schedule2((now, _: Any) =>
      Decision.Continue(Duration.Zero, Interval(now, now.plusNanos(duration.toNanos)), StepFunction.done(duration))
    )

  /**
   * A schedule that recurs once for each of the specified durations, delaying
   * each time for the length of the specified duration. Returns the length of
   * the current duration between recurrences.
   */
  def fromDurations(duration: Duration, durations: Duration*): Schedule2[Any, Duration] =
    durations.foldLeft(fromDuration(duration)) {
      case (acc, d) => acc ++ fromDuration(d)
    }

  /**
   * A schedule that recurs forever, mapping input values through the
   * specified function.
   */
  def fromFunction[A, B](f: A => B): Schedule2[A, B] = identity[A].map(f)

  val count: Schedule2[Any, Long] =
    unfold(0L)(_ + 1L)

  def identity[A]: Schedule2[A, A] = {
    lazy val loop: StepFunction[A, A] = (_: Instant, in: A) => Decision.Continue(in, Interval.Everywhere, loop)

    Schedule2(loop)
  }

  /**
   * A schedule that always recurs, but will repeat on a linear time
   * interval, given by `base * n` where `n` is the number of
   * repetitions so far. Returns the current duration between recurrences.
   */
  def linear(base: Duration): Schedule2[Any, Duration] =
    unfoldDelay(Duration.Zero -> 0L) {
      case (_, i) =>
        val delay = base * (i + 1).doubleValue()

        (delay, (delay, i + 1))
    }.map(_._1)

  /**
   * A schedule spanning all time, which can be stepped only the specified number of times before
   * it terminates.
   */
  def recurs(n: Int): Schedule2[Any, Long] =
    count.whileOutput(_ < n)

  def spaced(duration: Duration): Schedule2[Any, Long] = 
    unfoldDelay(0L)(i => (duration, i))

  def succeed[A](a0: => A): Schedule2[Any, A] = {
    lazy val a = a0

    lazy val loop: StepFunction[Any, A] = (_, _) => Decision.Continue(a, Interval.Everywhere, loop)

    Schedule2(loop)
  }

  def unfold[A](a: => A)(f: A => A): Schedule2[Any, A] = {
    def loop(a: => A): StepFunction[Any, A] =
      (_, _) => Decision.Continue(a, Interval.Everywhere, loop(f(a)))

    Schedule2(loop(a))
  }

  def unfoldDelay[A](a: => A)(f: A => (Duration, A)): Schedule2[Any, A] = {
    def loop(a0: => A): StepFunction[Any, A] = {
      val (delay, a) = f(a0)

      (now, _) => Decision.Continue(a0, Interval(now, now.plusNanos(delay.toNanos)), loop(a))
    }

    Schedule2(loop(a))
  }

  private def min(l: Instant, r: Instant): Instant = if (l.compareTo(r) <= 0) l else r
  private def max(l: Instant, r: Instant): Instant = if (l.compareTo(r) >= 0) l else r

  /**
   * An `Interval` represents an interval of time. Intervals can encompass all time, or no time
   * at all.
   *
   * @param start
   * @param end
   */
  final case class Interval(start: Instant, end: Instant) { self =>
    def <(that: Interval): Boolean = (self min that) == self

    def <=(that: Interval): Boolean = (self < that) || (self == that)

    def >(that: Interval): Boolean = that < self

    def >=(that: Interval): Boolean = (self > that) || (self == that)

    def after: Interval = Interval(end, Instant.MAX).normalize

    def after(that: Instant): Interval = if (start.compareTo(that) <= 0) Interval(that, end).normalize else self

    def before: Interval = Interval(Instant.MIN, start).normalize

    def before(that: Instant): Interval = if (end.compareTo(that) <= 0) Interval(start, that).normalize else self

    def complement: (Interval, Interval) = (self.before, self.after)

    override def equals(that: Any): Boolean =
      that match {
        case that @ Interval(_, _) =>
          val self1 = self.normalize
          val that1 = that.normalize

          self1.start == that1.start && self1.end == that1.end

        case _ => false
      }

    def everywhere: Boolean = normalize == Interval.Everywhere

    def intersect(that: Interval): Interval = {
      val start = Schedule2.max(self.start, that.start)
      val end   = Schedule2.min(self.end, that.end)

      if (start.compareTo(end) <= 0) Interval.Nowhere
      else Interval(start, end)
    }

    def max(that: Interval): Interval = {
      val m = self min that

      if (m == self) that else self
    }

    def min(that: Interval): Interval =
      if (self.end.compareTo(that.start) <= 0) self
      else if (that.end.compareTo(self.start) <= 0) that
      else if (self.start.compareTo(that.start) <= 0) self
      else if (that.start.compareTo(self.start) <= 0) that
      else if (self.end.compareTo(that.end) <= 0) self
      else that

    def normalize: Interval = if (start.compareTo(end) >= 0) Interval.Nowhere else self

    def nowhere: Boolean = normalize == Interval.Nowhere

    def overlaps(that: Interval): Boolean = !self.intersect(that).nowhere

    def shift(duration: Duration): Interval =
      Interval(
        Try(start.plusNanos(duration.toNanos)).getOrElse(Instant.MAX),
        Try(end.plusNanos(duration.toNanos)).getOrElse(Instant.MAX)
      ).normalize

    def shiftN(n: Long)(duration: Duration): Interval =
      Interval(
        Try(start.plusMillis(n * duration.toMillis)).getOrElse(Instant.MAX),
        Try(end.plusMillis(n * duration.toMillis)).getOrElse(Instant.MAX)
      ).normalize

    def union(that: Interval): Option[Interval] =
      if (self.nowhere) Some(that)
      else if (that.nowhere) Some(self)
      else {
        val istart = Schedule2.max(self.start, that.start)
        val iend   = Schedule2.min(self.end, that.end)

        if (istart.compareTo(iend) <= 0) None
        else Some(Interval(Schedule2.min(self.start, that.start), Schedule2.max(self.end, that.end)))
      }
  }
  object Interval {
    val Everywhere = Interval(Instant.MIN, Instant.MAX)
    val Nowhere    = Interval(Instant.MIN, Instant.MIN)
  }

  type StepFunction[-In, +Out] = (Instant, In) => Schedule2.Decision[In, Out]
  object StepFunction {
    def done[A](a: => A): StepFunction[Any, A] = (_: Instant, _: Any) => Decision.Done(a)

    def stepIfNecessary[In, Out](
      now: Instant,
      in: In,
      option: Option[(Interval, Out)],
      step0: StepFunction[In, Out]
    ): Decision[In, Out] =
      option match {
        case None => step0(now, in)
        case Some((interval, out)) =>
          if (now.compareTo(interval.end) >= 0) step0(now, in)
          else Decision.Continue(out, interval, step0)
      }
  }

  sealed trait Decision[-In, +Out] { self =>
    def out: Out

    def contramap[In1](f: In1 => In): Decision[In1, Out] =
      self match {
        case Decision.Done(v) => Decision.Done(v)
        case Decision.Continue(v, i, n) =>
          Decision.Continue(v, i, (now: Instant, in1: In1) => n(now, f(in1)).contramap(f))
      }

    def map[Out2](f: Out => Out2): Decision[In, Out2] =
      self match {
        case Decision.Done(v)           => Decision.Done(f(v))
        case Decision.Continue(v, i, n) => Decision.Continue(f(v), i, (now: Instant, in: In) => n(now, in).map(f))
      }

    def toDone: Decision[Any, Out] =
      self match {
        case Decision.Done(v)           => Decision.Done(v)
        case Decision.Continue(v, _, _) => Decision.Done(v)
      }
  }
  object Decision {
    final case class Done[+Out](out: Out) extends Decision[Any, Out]
    // [Inclusive, Exclusive)
    final case class Continue[-In, +Out](out: Out, interval: Interval, next: StepFunction[In, Out])
        extends Decision[In, Out]
  }
}
