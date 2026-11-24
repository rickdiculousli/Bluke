package dev.arnv.bluke.mirror

import dev.arnv.bluke.mirror.MirrorVectors.big
import dev.arnv.bluke.mirror.MirrorVectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Spake2Test {
  private fun checkVector(v: JSONObject, label: String) {
    val a = hex(v.getString("A"))
    val b = hex(v.getString("B"))
    val aad = hex(v.getString("aad"))
    val w = big(v.getString("w"))
    val partyA = Spake2Party(Spake2Role.A, a, b, w, aad, big(v.getString("x")))
    val partyB = Spake2Party(Spake2Role.B, a, b, w, aad, big(v.getString("y")))
    assertArrayEquals("$label pA", hex(v.getString("pA")), partyA.share)
    assertArrayEquals("$label pB", hex(v.getString("pB")), partyB.share)

    val atA = partyA.finish(partyB.share)
    val atB = partyB.finish(partyA.share)
    assertArrayEquals("$label TT", hex(v.getString("TT")), atA.transcript)
    assertArrayEquals("$label TT at B", hex(v.getString("TT")), atB.transcript)
    assertArrayEquals("$label cA", hex(v.getString("cA")), atA.ownMac)
    assertArrayEquals("$label cB", hex(v.getString("cB")), atB.ownMac)
    assertTrue(atA.verifyPeer(hex(v.getString("cB"))))
    assertTrue(atB.verifyPeer(hex(v.getString("cA"))))
    assertFalse(atA.verifyPeer(hex(v.getString("cA"))))
  }

  @Test
  fun rfc9382VectorsInBothRoles() {
    val vectors = MirrorVectors.spake2.getJSONArray("rfc9382")
    assertEquals(4, vectors.length())
    for (i in 0 until vectors.length()) checkVector(vectors.getJSONObject(i), "rfc9382[$i]")
  }

  @Test
  fun blukeVector() {
    val v = MirrorVectors.spake2.getJSONObject("bluke")
    assertArrayEquals(hex(v.getString("aad")), Spake2.AAD)
    assertEquals(big(v.getString("w")), Spake2.codeToW(v.getString("code")))
    checkVector(v, "bluke")
  }

  @Test
  fun codeToW() {
    val vectors = MirrorVectors.spake2.getJSONArray("code_to_w")
    for (i in 0 until vectors.length()) {
      val v = vectors.getJSONObject(i)
      assertEquals(v.getString("code"), big(v.getString("w")), Spake2.codeToW(v.getString("code")))
    }
  }

  @Test
  fun mAndNMatchTheVectors() {
    assertArrayEquals(P256.encode(P256.decode(hex(MirrorVectors.spake2.getString("M")))!!), P256.encode(Spake2.M))
    assertArrayEquals(P256.encode(P256.decode(hex(MirrorVectors.spake2.getString("N")))!!), P256.encode(Spake2.N))
  }

  @Test
  fun aWrongCodeFailsConfirmation() {
    val a = ByteArray(32) { 1 }
    val b = ByteArray(32) { 2 }
    val partyA = Spake2Party(Spake2Role.A, a, b, Spake2.codeToW("111111"), Spake2.AAD, Spake2.randomScalar())
    val partyB = Spake2Party(Spake2Role.B, a, b, Spake2.codeToW("111112"), Spake2.AAD, Spake2.randomScalar())
    val atA = partyA.finish(partyB.share)
    val atB = partyB.finish(partyA.share)
    assertFalse(atB.verifyPeer(atA.ownMac))
    assertFalse(atA.verifyPeer(atB.ownMac))
  }

  @Test
  fun badSharesAreRefused() {
    val v = MirrorVectors.spake2.getJSONObject("bluke")
    val party =
        Spake2Party(Spake2Role.B, hex(v.getString("A")), hex(v.getString("B")), big(v.getString("w")), Spake2.AAD, big(v.getString("y")))
    val offCurve = hex(v.getString("pA")).also { it[64] = (it[64] + 1).toByte() }
    val compressed = hex(MirrorVectors.spake2.getString("M"))
    val identity = byteArrayOf(0)
    // pA = w·M exactly, so pA − w·M is the identity and K would be too.
    val wM = P256.encode(P256.multiply(Spake2.M, big(v.getString("w"))))
    for (share in listOf(offCurve, compressed, identity, ByteArray(65), wM)) {
      try {
        party.finish(share)
        fail("accepted ${share.toHex()}")
      } catch (_: Spake2Exception) {}
    }
  }

  @Test
  fun randomCodesAreSixDigits() {
    repeat(100) { assertTrue(Spake2.randomCode().matches(Regex("\\d{6}"))) }
  }
}
