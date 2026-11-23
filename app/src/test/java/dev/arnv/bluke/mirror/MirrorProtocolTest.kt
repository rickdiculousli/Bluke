package dev.arnv.bluke.mirror

import dev.arnv.bluke.mirror.MirrorVectors.hex
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MirrorProtocolTest {
  private fun fieldsOf(m: MirrorMessage, f: JSONObject): MirrorMessage =
      when (m) {
        is MirrorMessage.PairRequest -> MirrorMessage.PairRequest(f.getString("name"))
        is MirrorMessage.PairCode -> MirrorMessage.PairCode(f.getInt("attempts_left"))
        is MirrorMessage.PakeShare -> MirrorMessage.PakeShare(hex(f.getString("pA")))
        is MirrorMessage.PakeReply -> MirrorMessage.PakeReply(hex(f.getString("pB")), hex(f.getString("cB")))
        is MirrorMessage.PakeConfirm -> MirrorMessage.PakeConfirm(hex(f.getString("cA")))
        is MirrorMessage.PairResult -> MirrorMessage.PairResult(f.getInt("status"))
        is MirrorMessage.Refused -> MirrorMessage.Refused(f.getInt("reason"))
        is MirrorMessage.Hello -> MirrorMessage.Hello(f.getInt("version"), f.getString("name"))
        is MirrorMessage.Welcome ->
            MirrorMessage.Welcome(
                f.getInt("version"), f.getInt("codecs"), f.getInt("max_width"), f.getInt("max_height"), f.getInt("fps"))
        is MirrorMessage.Display ->
            MirrorMessage.Display(f.getLong("id"), f.getLong("width"), f.getLong("height"), f.getString("name"))
        is MirrorMessage.Frame ->
            MirrorMessage.Frame(
                f.getLong("seq"), f.getInt("codec"), f.getInt("width"), f.getInt("height"),
                f.getLong("sent_us"), hex(f.getString("data")))
        is MirrorMessage.FrameAck -> MirrorMessage.FrameAck(f.getLong("seq"), f.getLong("decode_us"))
        is MirrorMessage.Pointer -> MirrorMessage.Pointer(f.getInt("x"), f.getInt("y"), f.getInt("buttons"))
        is MirrorMessage.Wheel -> MirrorMessage.Wheel(f.getInt("dx"), f.getInt("dy"))
        MirrorMessage.Heartbeat -> MirrorMessage.Heartbeat
        MirrorMessage.Release -> MirrorMessage.Release
        is MirrorMessage.Ping -> MirrorMessage.Ping(f.getLong("t_us"))
        is MirrorMessage.Pong -> MirrorMessage.Pong(f.getLong("t_us"))
        is MirrorMessage.Unknown -> error("not a named vector")
      }

  @Test
  fun everyMessageVectorDecodesToItsFieldsAndEncodesBack() {
    val messages = MirrorVectors.protocol.getJSONArray("messages")
    assertEquals(20, messages.length())
    for (i in 0 until messages.length()) {
      val v = messages.getJSONObject(i)
      val bytes = hex(v.getString("hex"))
      val decoded = MirrorCodec.decode(bytes)
      val name = v.getString("name")
      assertEquals(name, decoded::class.simpleName)
      assertEquals(name, v.getInt("type"), decoded.type)
      assertEquals(name, fieldsOf(decoded, v.getJSONObject("fields")), decoded)
      assertArrayEquals(name, bytes, MirrorCodec.encode(decoded))
      assertEquals(name, decoded, MirrorCodec.read(ByteArrayInputStream(bytes)))
      assertEquals(name, v.getInt("type") in 1..7, decoded.isPairing)
    }
  }

  @Test
  fun unknownTypeDecodesAsUnknown() {
    val v = MirrorVectors.protocol.getJSONArray("unknown").getJSONObject(0)
    val m = MirrorCodec.decode(hex(v.getString("hex")))
    assertEquals(MirrorMessage.Unknown(v.getInt("type")), m)
    assertFalse(m.isPairing)
  }

  @Test
  fun everyInvalidVectorIsRefusedWithItsKind() {
    val invalid = MirrorVectors.protocol.getJSONArray("invalid")
    for (i in 0 until invalid.length()) {
      val v = invalid.getJSONObject(i)
      val kind = MirrorProtocolException.Kind.valueOf(v.getString("error").uppercase())
      val bytes = hex(v.getString("hex"))
      assertEquals(v.getString("about"), kind, kindOf { MirrorCodec.decode(bytes) })
      assertEquals(v.getString("about"), kind, kindOf { MirrorCodec.read(ByteArrayInputStream(bytes)) })
    }
  }

  @Test
  fun readRefusesTooLargeFromTheHeaderAlone() {
    for (header in listOf("00040001", "ffffffff")) {
      val stream = HeaderOnlyStream(hex(header))
      assertEquals(MirrorProtocolException.Kind.TOO_LARGE, kindOf { MirrorCodec.read(stream) })
    }
  }

  @Test
  fun aHeaderAtExactlyTheCapIsAcceptedAndTheBodyRead() {
    val header = hex(MirrorVectors.protocol.getJSONObject("cap_header").getString("hex"))
    assertEquals(MIRROR_MAX_MESSAGE, MirrorVectors.protocol.getInt("cap"))
    val stream = HeaderOnlyStream(header)
    try {
      MirrorCodec.read(stream)
      fail("expected the body read")
    } catch (_: BodyRead) {}
  }

  @Test
  fun cleanEndOfStreamIsEofAndATruncatedMessageIsMalformed() {
    try {
      MirrorCodec.read(ByteArrayInputStream(ByteArray(0)))
      fail("expected EOF")
    } catch (_: EOFException) {}
    assertEquals(
        MirrorProtocolException.Kind.MALFORMED,
        kindOf { MirrorCodec.read(ByteArrayInputStream(hex("000000"))) })
    assertEquals(
        MirrorProtocolException.Kind.MALFORMED,
        kindOf { MirrorCodec.read(ByteArrayInputStream(hex("00000006 20 8000"))) })
  }

  @Test
  fun readsBackToBackMessages() {
    val a = MirrorMessage.Pointer(1, 2, 1)
    val b = MirrorMessage.Release
    val input = ByteArrayInputStream(MirrorCodec.encode(a) + MirrorCodec.encode(b))
    assertEquals(a, MirrorCodec.read(input))
    assertEquals(b, MirrorCodec.read(input))
    try {
      MirrorCodec.read(input)
      fail("expected EOF")
    } catch (_: EOFException) {}
  }

  @Test
  fun byteArrayFieldsCompareByContent() {
    assertEquals(MirrorMessage.PakeConfirm(ByteArray(32) { 1 }), MirrorMessage.PakeConfirm(ByteArray(32) { 1 }))
    assertTrue(MirrorMessage.PakeConfirm(ByteArray(32)) != MirrorMessage.PakeConfirm(ByteArray(32) { 1 }))
  }

  private class BodyRead : RuntimeException()

  /** Yields [header], then throws on any further read: proves nothing past the header was read. */
  private class HeaderOnlyStream(private val header: ByteArray) : InputStream() {
    private var pos = 0

    override fun read(): Int = if (pos < header.size) header[pos++].toInt() and 0xFF else throw BodyRead()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (pos >= header.size) throw BodyRead()
      val n = minOf(len, header.size - pos)
      System.arraycopy(header, pos, b, off, n)
      pos += n
      return n
    }
  }

  private fun kindOf(block: () -> Unit): MirrorProtocolException.Kind? =
      try {
        block()
        null
      } catch (e: MirrorProtocolException) {
        e.kind
      }
}
