package dev.arnv.bluke.mirror

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Largest `length` a message header may carry (type byte plus body), per `companion/PROTOCOL.md`. */
const val MIRROR_MAX_MESSAGE = 262_144

/** The TCP port the phone listens on while the Mirror surface is visible. */
const val MIRROR_PORT = 47800

/** The protocol version carried in `Hello` and `Welcome`. */
const val MIRROR_PROTOCOL_VERSION = 1

/** Longest `str` field, in UTF-8 bytes. */
const val MIRROR_MAX_STRING_BYTES = 64

/** Size of a SEC1 uncompressed P-256 point (`pA`, `pB`). */
const val MIRROR_POINT_BYTES = 65

/** Size of a SPAKE2 confirmation MAC (`cA`, `cB`). */
const val MIRROR_MAC_BYTES = 32

/** `FrameAck.decodeUs` meaning the frame was replaced before it was decoded. */
const val MIRROR_FRAME_DROPPED: Long = 0xFFFF_FFFFL

/** Codec bit / codec id for baseline JPEG. */
const val MIRROR_CODEC_JPEG = 1

/**
 * One message of the Mirror protocol (`companion/PROTOCOL.md`), one class per wire type.
 * Unsigned wire integers are held in a wider signed Kotlin type (u16 and u8 in [Int], u32 in [Long]);
 * `u64` fields are held in [Long] bit for bit. Byte-array fields compare by content.
 */
sealed interface MirrorMessage {
  /** The wire type byte. */
  val type: Int

  data class PairRequest(val name: String) : MirrorMessage {
    override val type get() = 0x01
  }

  data class PairCode(val attemptsLeft: Int) : MirrorMessage {
    override val type get() = 0x02
  }

  class PakeShare(val pA: ByteArray) : MirrorMessage {
    override val type get() = 0x03
    override fun equals(other: Any?) = other is PakeShare && pA.contentEquals(other.pA)
    override fun hashCode() = pA.contentHashCode()
    override fun toString() = "PakeShare(pA=${pA.toHex()})"
  }

  class PakeReply(val pB: ByteArray, val cB: ByteArray) : MirrorMessage {
    override val type get() = 0x04
    override fun equals(other: Any?) =
        other is PakeReply && pB.contentEquals(other.pB) && cB.contentEquals(other.cB)
    override fun hashCode() = 31 * pB.contentHashCode() + cB.contentHashCode()
    override fun toString() = "PakeReply(pB=${pB.toHex()}, cB=${cB.toHex()})"
  }

  class PakeConfirm(val cA: ByteArray) : MirrorMessage {
    override val type get() = 0x05
    override fun equals(other: Any?) = other is PakeConfirm && cA.contentEquals(other.cA)
    override fun hashCode() = cA.contentHashCode()
    override fun toString() = "PakeConfirm(cA=${cA.toHex()})"
  }

  data class PairResult(val status: Int) : MirrorMessage {
    override val type get() = 0x06

    companion object {
      const val PAIRED = 0
      const val WRONG_CODE = 1
      const val LOCKED = 2
      const val UNAVAILABLE = 3
    }
  }

  data class Refused(val reason: Int) : MirrorMessage {
    override val type get() = 0x07

    companion object {
      const val NOT_PAIRED = 1
      const val UNSUPPORTED_VERSION = 2
    }
  }

  data class Hello(val version: Int, val name: String) : MirrorMessage {
    override val type get() = 0x10
  }

  /** [codecs] is a bit set: bit 0 is JPEG. */
  data class Welcome(
      val version: Int = MIRROR_PROTOCOL_VERSION,
      val codecs: Int = MIRROR_CODEC_JPEG,
      val maxWidth: Int = 480,
      val maxHeight: Int = 480,
      val fps: Int = 20,
  ) : MirrorMessage {
    override val type get() = 0x11
  }

  data class Display(val id: Long, val width: Long, val height: Long, val name: String) :
      MirrorMessage {
    override val type get() = 0x12
  }

  /** [sentUs] is the companion's monotonic clock; [data] is at least one byte. */
  class Frame(
      val seq: Long,
      val codec: Int,
      val width: Int,
      val height: Int,
      val sentUs: Long,
      val data: ByteArray,
  ) : MirrorMessage {
    override val type get() = 0x13
    override fun equals(other: Any?) =
        other is Frame &&
            seq == other.seq &&
            codec == other.codec &&
            width == other.width &&
            height == other.height &&
            sentUs == other.sentUs &&
            data.contentEquals(other.data)
    override fun hashCode() =
        listOf(seq, codec, width, height, sentUs, data.contentHashCode()).hashCode()
    override fun toString() =
        "Frame(seq=$seq, codec=$codec, width=$width, height=$height, sentUs=$sentUs, data=${data.size} bytes)"
  }

  /** [decodeUs] is [MIRROR_FRAME_DROPPED] for a frame replaced before it was decoded. */
  data class FrameAck(val seq: Long, val decodeUs: Long) : MirrorMessage {
    override val type get() = 0x14
  }

  /** [x] and [y] are 0..65535 across the mirrored display; [buttons] bit 0 is the left button. */
  data class Pointer(val x: Int, val y: Int, val buttons: Int) : MirrorMessage {
    override val type get() = 0x20
  }

  /** Pixels; positive [dy] scrolls the view toward the top, positive [dx] toward the left. */
  data class Wheel(val dx: Int, val dy: Int) : MirrorMessage {
    override val type get() = 0x21
  }

  data object Heartbeat : MirrorMessage {
    override val type get() = 0x22
  }

  data object Release : MirrorMessage {
    override val type get() = 0x23
  }

  data class Ping(val tUs: Long) : MirrorMessage {
    override val type get() = 0x30
  }

  data class Pong(val tUs: Long) : MirrorMessage {
    override val type get() = 0x31
  }

  /** A type this version doesn't know; its body is discarded. A session skips it. */
  data class Unknown(override val type: Int) : MirrorMessage
}

/** Whether this is one of the pairing messages (types 1-7), the only ones an unverified connection carries. */
val MirrorMessage.isPairing: Boolean
  get() = type in 0x01..0x07

/** Why a message was refused. Every kind closes the connection. */
class MirrorProtocolException(val kind: Kind, message: String) : IOException("$kind: $message") {
  enum class Kind {
    /** Header length 0: no type byte. */
    EMPTY,
    /** Header length above [MIRROR_MAX_MESSAGE]. */
    TOO_LARGE,
    /** Body not exactly its fields, a bad string, or a stream that ended inside a message. */
    MALFORMED,
  }
}

/** Encodes and decodes whole framed messages: `u32 length | u8 type | body`. */
object MirrorCodec {
  /** The whole message, header included. Throws [IllegalArgumentException] for a field out of its wire range. */
  fun encode(m: MirrorMessage): ByteArray {
    val body = Writer()
    when (m) {
      is MirrorMessage.PairRequest -> body.str(m.name)
      is MirrorMessage.PairCode -> body.u8(m.attemptsLeft)
      is MirrorMessage.PakeShare -> body.fixed(m.pA, MIRROR_POINT_BYTES)
      is MirrorMessage.PakeReply -> {
        body.fixed(m.pB, MIRROR_POINT_BYTES)
        body.fixed(m.cB, MIRROR_MAC_BYTES)
      }
      is MirrorMessage.PakeConfirm -> body.fixed(m.cA, MIRROR_MAC_BYTES)
      is MirrorMessage.PairResult -> body.u8(m.status)
      is MirrorMessage.Refused -> body.u8(m.reason)
      is MirrorMessage.Hello -> {
        body.u16(m.version)
        body.str(m.name)
      }
      is MirrorMessage.Welcome -> {
        body.u16(m.version)
        body.u8(m.codecs)
        body.u16(m.maxWidth)
        body.u16(m.maxHeight)
        body.u8(m.fps)
      }
      is MirrorMessage.Display -> {
        body.u32(m.id)
        body.u32(m.width)
        body.u32(m.height)
        body.str(m.name)
      }
      is MirrorMessage.Frame -> {
        require(m.data.isNotEmpty()) { "Frame data must not be empty" }
        body.u32(m.seq)
        body.u8(m.codec)
        body.u16(m.width)
        body.u16(m.height)
        body.u64(m.sentUs)
        body.raw(m.data)
      }
      is MirrorMessage.FrameAck -> {
        body.u32(m.seq)
        body.u32(m.decodeUs)
      }
      is MirrorMessage.Pointer -> {
        body.u16(m.x)
        body.u16(m.y)
        body.u8(m.buttons)
      }
      is MirrorMessage.Wheel -> {
        body.i16(m.dx)
        body.i16(m.dy)
      }
      MirrorMessage.Heartbeat, MirrorMessage.Release -> Unit
      is MirrorMessage.Ping -> body.u64(m.tUs)
      is MirrorMessage.Pong -> body.u64(m.tUs)
      is MirrorMessage.Unknown -> throw IllegalArgumentException("cannot encode an Unknown message")
    }
    val bodyBytes = body.toByteArray()
    val length = 1 + bodyBytes.size
    require(length <= MIRROR_MAX_MESSAGE) { "message of $length bytes is over the cap" }
    return ByteBuffer.allocate(4 + length).putInt(length).put(m.type.toByte()).put(bodyBytes).array()
  }

  /**
   * Reads one message. The length header is checked against [MIRROR_MAX_MESSAGE] before the body
   * is allocated or read. Throws [EOFException] when the stream ends cleanly before a header's
   * first byte, and [MirrorProtocolException] for anything else wrong, including a stream that
   * ends inside a message ([MirrorProtocolException.Kind.MALFORMED]).
   */
  fun read(input: InputStream): MirrorMessage {
    val header = ByteArray(4)
    val first = input.read()
    if (first < 0) throw EOFException("end of stream between messages")
    header[0] = first.toByte()
    readFully(input, header, 1, 3)
    val length = checkLength(ByteBuffer.wrap(header).int.toLong() and 0xFFFF_FFFFL)
    val rest = ByteArray(length)
    readFully(input, rest, 0, length)
    return decodeBody(rest[0].toInt() and 0xFF, rest, 1)
  }

  /** Decodes [whole], which must be exactly one message, header included. */
  fun decode(whole: ByteArray): MirrorMessage {
    if (whole.size < 4) throw malformed("message shorter than its header")
    val length = checkLength(ByteBuffer.wrap(whole, 0, 4).int.toLong() and 0xFFFF_FFFFL)
    if (whole.size - 4 != length) throw malformed("header says $length bytes, got ${whole.size - 4}")
    return decodeBody(whole[4].toInt() and 0xFF, whole, 5)
  }

  private fun checkLength(length: Long): Int {
    if (length == 0L) throw MirrorProtocolException(MirrorProtocolException.Kind.EMPTY, "length 0")
    if (length > MIRROR_MAX_MESSAGE) {
      throw MirrorProtocolException(MirrorProtocolException.Kind.TOO_LARGE, "length $length")
    }
    return length.toInt()
  }

  private fun readFully(input: InputStream, into: ByteArray, offset: Int, count: Int) {
    var done = 0
    while (done < count) {
      val n = input.read(into, offset + done, count - done)
      if (n < 0) throw malformed("stream ended inside a message")
      done += n
    }
  }

  private fun decodeBody(type: Int, bytes: ByteArray, start: Int): MirrorMessage {
    val r = Reader(bytes, start)
    val m: MirrorMessage =
        when (type) {
          0x01 -> MirrorMessage.PairRequest(r.str())
          0x02 -> MirrorMessage.PairCode(r.u8())
          0x03 -> MirrorMessage.PakeShare(r.bytes(MIRROR_POINT_BYTES))
          0x04 -> MirrorMessage.PakeReply(r.bytes(MIRROR_POINT_BYTES), r.bytes(MIRROR_MAC_BYTES))
          0x05 -> MirrorMessage.PakeConfirm(r.bytes(MIRROR_MAC_BYTES))
          0x06 -> MirrorMessage.PairResult(r.u8())
          0x07 -> MirrorMessage.Refused(r.u8())
          0x10 -> MirrorMessage.Hello(r.u16(), r.str())
          0x11 -> MirrorMessage.Welcome(r.u16(), r.u8(), r.u16(), r.u16(), r.u8())
          0x12 -> MirrorMessage.Display(r.u32(), r.u32(), r.u32(), r.str())
          0x13 -> {
            val seq = r.u32()
            val codec = r.u8()
            val width = r.u16()
            val height = r.u16()
            val sentUs = r.u64()
            val data = r.rest()
            if (data.isEmpty()) throw malformed("Frame with no data")
            MirrorMessage.Frame(seq, codec, width, height, sentUs, data)
          }
          0x14 -> MirrorMessage.FrameAck(r.u32(), r.u32())
          0x20 -> MirrorMessage.Pointer(r.u16(), r.u16(), r.u8())
          0x21 -> MirrorMessage.Wheel(r.i16(), r.i16())
          0x22 -> MirrorMessage.Heartbeat
          0x23 -> MirrorMessage.Release
          0x30 -> MirrorMessage.Ping(r.u64())
          0x31 -> MirrorMessage.Pong(r.u64())
          else -> {
            r.rest()
            MirrorMessage.Unknown(type)
          }
        }
    r.end()
    return m
  }

  private fun malformed(why: String) =
      MirrorProtocolException(MirrorProtocolException.Kind.MALFORMED, why)

  private class Reader(private val b: ByteArray, private var pos: Int) {
    private fun take(n: Int): Int {
      if (b.size - pos < n) throw malformed("body too short")
      val at = pos
      pos += n
      return at
    }

    fun u8(): Int = b[take(1)].toInt() and 0xFF

    fun u16(): Int = ByteBuffer.wrap(b, take(2), 2).short.toInt() and 0xFFFF

    fun i16(): Int = ByteBuffer.wrap(b, take(2), 2).short.toInt()

    fun u32(): Long = ByteBuffer.wrap(b, take(4), 4).int.toLong() and 0xFFFF_FFFFL

    fun u64(): Long = ByteBuffer.wrap(b, take(8), 8).long

    fun bytes(n: Int): ByteArray = take(n).let { b.copyOfRange(it, it + n) }

    fun rest(): ByteArray = bytes(b.size - pos)

    fun str(): String {
      val n = u8()
      if (n > MIRROR_MAX_STRING_BYTES) throw malformed("string of $n bytes")
      val raw = bytes(n)
      return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
      } catch (_: CharacterCodingException) {
        throw malformed("string not UTF-8")
      }
    }

    fun end() {
      if (pos != b.size) throw malformed("${b.size - pos} bytes past the fields")
    }
  }

  private class Writer {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) {
      require(v in 0..0xFF) { "u8 out of range: $v" }
      out.write(v)
    }

    fun u16(v: Int) {
      require(v in 0..0xFFFF) { "u16 out of range: $v" }
      out.write(v ushr 8)
      out.write(v and 0xFF)
    }

    fun i16(v: Int) {
      require(v in Short.MIN_VALUE..Short.MAX_VALUE) { "i16 out of range: $v" }
      out.write((v shr 8) and 0xFF)
      out.write(v and 0xFF)
    }

    fun u32(v: Long) {
      require(v in 0..0xFFFF_FFFFL) { "u32 out of range: $v" }
      out.write(ByteBuffer.allocate(4).putInt(v.toInt()).array())
    }

    fun u64(v: Long) = out.write(ByteBuffer.allocate(8).putLong(v).array())

    fun fixed(v: ByteArray, size: Int) {
      require(v.size == size) { "expected $size bytes, got ${v.size}" }
      out.write(v)
    }

    fun raw(v: ByteArray) = out.write(v)

    fun str(v: String) {
      val bytes = v.toByteArray(Charsets.UTF_8)
      require(bytes.size <= MIRROR_MAX_STRING_BYTES) { "string of ${bytes.size} bytes" }
      out.write(bytes.size)
      out.write(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
  }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
