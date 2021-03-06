package scodec.protocols
package time

import language.implicitConversions

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.time.Instant
import cats.effect._
import cats.implicits._
import fs2._
import scodec.bits._

class TimeStampedTest extends ProtocolsSpec {

  "the TimeStamped type" should {

    "support calculation of rates" which {

      implicit def intToInstant(x: Int): Instant = Instant.ofEpochSecond(x.toLong)
      implicit def doubleToInstant(x: Double): Instant = Instant.ofEpochSecond(x.toLong)

      "emits accumulated feature values for each specified time period and emits a final value" in {
        val data = Stream(
          TimeStamped(0, 1),
          TimeStamped(0.5, 2),
          TimeStamped(1, 1),
          TimeStamped(2.3, 2))

        data.through(TimeStamped.rate(1.second)(identity[Int]).toPipe).toVector shouldBe Vector(
          TimeStamped(1, 3), TimeStamped(2, 1), TimeStamped(3, 2))

        data.through(TimeStamped.rate(2.seconds)(identity[Int]).toPipe).toVector shouldBe Vector(TimeStamped(2, 4), TimeStamped(4, 2))
      }

      "emits 0s when values are skipped over" in {
        val data = Stream(TimeStamped(0, 1), TimeStamped(3.3, 2))
        data.through(TimeStamped.rate(1.second)(identity[Int]).toPipe).toVector shouldBe Vector(
          TimeStamped(1, 1), TimeStamped(2, 0), TimeStamped(3, 0), TimeStamped(4, 2))

        data.through(TimeStamped.withRate(1.second)(identity[Int]).toPipe).toVector shouldBe Vector(
          TimeStamped(0, Right(1)), TimeStamped(1, Left(1)), TimeStamped(2, Left(0)), TimeStamped(3, Left(0)), TimeStamped(3.3, Right(2)), TimeStamped(4, Left(2)))
      }

      "supports calculation of an average bitrate" in {
        val data = Stream(
          TimeStamped(0, hex"deadbeef"),
          TimeStamped(1, hex"deadbeef"),
          TimeStamped(1.5, hex"deadbeef"),
          TimeStamped(2.5, hex"deadbeef"),
          TimeStamped(2.6, hex"deadbeef")
        )

        val bitsPerSecond = data.through(TimeStamped.rate(1.second)((x: ByteVector) => x.size * 8L).toPipe)

        case class Average(samples: Int, value: Double)
        val zero = Average(0, 0)
        val combineAverages = (x: Average, y: Average) => {
          val totalSamples = x.samples + y.samples
          val avg = ((x.samples * x.value) + (y.samples * y.value)) / totalSamples
          Average(totalSamples, avg)
        }

        val avgBitrate = bitsPerSecond.toVector.foldLeft(zero) { (acc, bits) => combineAverages(acc, Average(1, bits.value.toDouble)) }
        avgBitrate.value shouldBe 53.3 +- 1.0
      }
    }

    "support filtering a source of timestamped values such that output is monotonically increasing in time" which {
      def ts(value: Int) = TimeStamped(Instant.ofEpochSecond(value.toLong), ())
      val data = Stream(0, -2, -1, 1, 5, 3, 6).map(ts)

      "supports dropping out-of-order values" in {
        val filtered = data through TimeStamped.increasing
        filtered.toList shouldBe List(ts(0), ts(1), ts(5), ts(6))
      }

      "supports receiving out-of-order values" in {
        val filtered = data through TimeStamped.increasingW
        filtered.toList shouldBe List(Right(ts(0)), Left(ts(-2)), Left(ts(-1)), Right(ts(1)), Right(ts(5)), Left(ts(3)), Right(ts(6)))
      }
    }

    "support reordering timestamped values over a specified time buffer such that output is monotonically increasing in time" which {
      def ts(value: Int) = TimeStamped(Instant.ofEpochMilli(value.toLong), value.toLong)

      val onTheSecond = Stream.emits(1 to 10).map { x => ts(x * 1000) }
      val onTheQuarterPast = onTheSecond map { _ mapTime { t => t.plusMillis(250) } }

      "reorders when all out of order values lie within the buffer time" in {
        val inOrder = onTheSecond interleave onTheQuarterPast
        val outOfOrder = onTheQuarterPast interleave onTheSecond
        val reordered = outOfOrder through TimeStamped.reorderLocally(1.second)
        reordered.toList shouldBe inOrder.toList
      }

      "drops values that appear outside the buffer time" in {
        // Create mostly ordered data with clumps of values around each second that are unordered
        val events = Stream.emits(1 to 10).flatMap { x =>
          val local = (-10 to 10).map { y => ts((x * 1000) + (y * 10)) }
          Stream.emits(scala.util.Random.shuffle(local))
        }
        val reordered200ms = events through TimeStamped.reorderLocally(200.milliseconds)
        reordered200ms.toList shouldBe events.toList.sorted

        val reordered20ms = events through TimeStamped.reorderLocally(20.milliseconds)
        reordered20ms.toList.size should be >= 10
      }

      "emits values with the same timestamp in insertion order" in {
        val onTheSecondBumped = onTheSecond map { _ map { _ + 1 } }
        val inOrder = (onTheSecond interleave onTheQuarterPast) interleave (onTheSecondBumped interleave onTheQuarterPast)
        val outOfOrder = (onTheQuarterPast interleave onTheSecond) interleave (onTheQuarterPast interleave onTheSecondBumped)
        val reordered = outOfOrder through TimeStamped.reorderLocally(1.second)
        reordered.toList shouldBe inOrder.toList
      }
    }

    "support throttling a time stamped source" in {
      implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
      implicit val timerIO: Timer[IO] = IO.timer(ExecutionContext.global)
      def ts(value: Int) = TimeStamped(Instant.ofEpochSecond(value.toLong), value.toLong)
      val source = Stream(ts(0), ts(1), ts(2), ts(3), ts(4)).covary[IO]
      def time[A](f: => A): Long = {
        val start = System.nanoTime
        val _ = f
        System.nanoTime - start
      }
      val realtime = source.through(TimeStamped.throttle[IO, Long](1.0)).compile.drain
      time(realtime.unsafeRunTimed(5.seconds)) shouldBe 4.seconds.toNanos +- 250.millis.toNanos
      val doubletime = source.through(TimeStamped.throttle[IO, Long](2.0)).compile.drain
      time(doubletime.unsafeRunTimed(3.seconds)) shouldBe 2.seconds.toNanos +- 250.millis.toNanos
    }

    "support lifting a Transform.Aux[S, TimeStamped[A], TimeStamped[B]] in to a Transform.Aux[S, TimeStamped[Either[A, C]], TimeStamped[Either[B, C]]]" in {
      val source = Stream(
        TimeStamped(Instant.ofEpochMilli(1), Left(1)),
        TimeStamped(Instant.ofEpochMilli(2), Right(2)),
        TimeStamped(Instant.ofEpochMilli(3), Right(3)),
        TimeStamped(Instant.ofEpochMilli(4), Left(4)),
        TimeStamped(Instant.ofEpochMilli(5), Left(5)),
        TimeStamped(Instant.ofEpochMilli(6), Right(6))
      )
      val square: Transform.Aux[Unit, TimeStamped[Int], TimeStamped[Int]] = Transform.lift(_.map { x => x * x })
      source.through(TimeStamped.left(square).toPipe).toVector shouldBe Vector(
        TimeStamped(Instant.ofEpochMilli(1), Left(1)),
        TimeStamped(Instant.ofEpochMilli(2), Right(2)),
        TimeStamped(Instant.ofEpochMilli(3), Right(3)),
        TimeStamped(Instant.ofEpochMilli(4), Left(16)),
        TimeStamped(Instant.ofEpochMilli(5), Left(25)),
        TimeStamped(Instant.ofEpochMilli(6), Right(6))
      )
    }
  }
}
