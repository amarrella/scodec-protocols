package scodec.protocols
package mpeg
package transport

import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._

import fs2._

/** Transport stream packet. */
case class Packet(
  header: TransportStreamHeader,
  adaptationField: Option[AdaptationField],
  payloadUnitStart: Option[Int],
  payload: Option[BitVector]
)

object Packet {

  def packetize(pid: Pid, startingCountinuityCounter: ContinuityCounter, section: BitVector): Vector[Packet] = {
    @annotation.tailrec
    def go(first: Boolean, cc: ContinuityCounter, remaining: BitVector, acc: Vector[Packet]): Vector[Packet] = {
      if (remaining.isEmpty) acc
      else {
        val (packetData, remData) = remaining.splitAt(8L * (if (first) 183 else 184))
        go(false, cc.next, remData, acc :+ payload(pid, cc, if (first) Some(0) else None, packetData))
      }
    }
    go(true, startingCountinuityCounter, section, Vector.empty)
  }

  def packetizeMany(pid: Pid, startingCountinuityCounter: ContinuityCounter, sections: Vector[BitVector]): Vector[Packet] = {

    /*
     * Accumulates up to `n` bits from the specified bit vectors.
     * Returns a triple consisting of:
     *  - the accumulated bits (up to size `n`)
     *  - the left over bits of the last consumed input section
     *  - the remaining unconsumed sections
     */
    def accumulateN(n: Long, sections: Vector[BitVector]): (BitVector, BitVector, Vector[BitVector]) = {
      @annotation.tailrec
      def go(needed: Long, remainingSections: Vector[BitVector], acc: BitVector): (BitVector, BitVector, Vector[BitVector]) = {
        if (remainingSections.isEmpty) (acc, BitVector.empty, Vector.empty)
        else {
          val (x, rem) = remainingSections.head.splitAt(needed)
          val newAcc = acc ++ x
          val left = needed - x.size
          if (left == 0) (newAcc, rem, remainingSections.tail)
          else go(left, remainingSections.tail, newAcc)
        }
      }
      go(n, sections, BitVector.empty)
    }

    @annotation.tailrec
    def go(cc: ContinuityCounter, remaining: BitVector, remainingSections: Vector[BitVector], acc: Vector[Packet]): Vector[Packet] = {
      if (remaining.isEmpty && remainingSections.isEmpty) acc
      else {
        val (packetData, overflow, remSections) = accumulateN(184 * 8, remaining +: remainingSections)
        val payloadUnitStart = {
          if (remSections.size < remainingSections.size) Some((remaining.size / 8).toInt)
          else None
        }
        val (adjPacketData, adjOverflow) = {
          if (payloadUnitStart.isDefined) (packetData.take(183 * 8), packetData.drop(183 * 8) ++ overflow)
          else (packetData, overflow)
        }
        val packet = payload(pid, cc, payloadUnitStart, adjPacketData)
        go(cc.next, adjOverflow, remSections, acc :+ packet)
      }
    }
    go(startingCountinuityCounter, BitVector.empty, sections, Vector.empty)
  }

  def payload(pid: Pid, continuityCounter: ContinuityCounter, payloadUnitStart: Option[Int], payload: BitVector): Packet = {
    val thisPid = pid
    val thisContinuityCounter = continuityCounter
    val thisPayloadUnitStart = payloadUnitStart
    val payloadLength = 8 * (if (payloadUnitStart.isDefined) 183 else 184)
    require(payload.length <= payloadLength, s"payload too long; must be <= $payloadLength")
    val thisPayload = payload ++ BitVector.high(payloadLength - payload.length)
    Packet(
      header = TransportStreamHeader(
        transportErrorIndicator = false,
        payloadUnitStartIndicator = payloadUnitStart.isDefined,
        transportPriority = false,
        pid = thisPid,
        scramblingControl = 0,
        adaptationFieldControl = 1,
        continuityCounter = thisContinuityCounter
      ),
      adaptationField = None,
      payloadUnitStart = thisPayloadUnitStart,
      payload = Some(thisPayload))
  }

  implicit def codec(implicit adaptationField: Codec[AdaptationField]): Codec[Packet] =
    "packet" | fixedSizeBytes(188,
      ("header"            | Codec[TransportStreamHeader]                              ) >>:~ { hdr =>
      ("adaptation_field"  | conditional(hdr.adaptationFieldIncluded, adaptationField) ) ::
      ("payload_start_ind" | conditional(hdr.payloadUnitStartIndicator, uint8)         ) ::
      ("payload"           | conditional(hdr.payloadIncluded, bits)                    )
    }).as[Packet]

  def validateContinuity: Transform.Aux[Map[Pid, ContinuityCounter], Packet, Either[PidStamped[DemultiplexerError.Discontinuity], Packet]] =
    Transform.stateful[Map[Pid, ContinuityCounter], Packet, Either[PidStamped[DemultiplexerError.Discontinuity], Packet]](Map.empty) { (state, packet) =>
      val pid = packet.header.pid
      val currentContinuityCounter = packet.header.continuityCounter
      val err = state.get(pid).map { lastContinuityCounter =>
        if (lastContinuityCounter.next == currentContinuityCounter) {
          None
        } else {
          val err: Either[PidStamped[DemultiplexerError.Discontinuity], Packet] = Left(PidStamped(pid, DemultiplexerError.Discontinuity(lastContinuityCounter, currentContinuityCounter)))
          Some(err)
        }
      }.getOrElse(None)
      val newState = state + (pid -> currentContinuityCounter)
      val out = err.map { e => Chunk(e, Right(packet)) }.getOrElse(Chunk.singleton(Right(packet)))
      (newState, out)
    }
}
