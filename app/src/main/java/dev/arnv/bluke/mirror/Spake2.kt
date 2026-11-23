package dev.arnv.bluke.mirror

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 with the suite P256-SHA256-HKDF-HMAC from RFC 9382, as `companion/PROTOCOL.md` "Pairing"
 * uses it: A is the companion, B the phone, identities are the raw certificate fingerprints.
 *
 * The arithmetic runs on [BigInteger], which isn't constant-time, so timing could leak bits of a
 * party's scalar or of w. That is acceptable here: every code is fresh, random, and dies after one
 * PakeShare, so an attacker gets one online guess per code and no second run to correlate timings
 * against, and the scalar is never reused.
 */
object Spake2 {
  /** The AAD every Bluke run binds into its confirmation keys: ASCII `bluke-mirror v1`. */
  val AAD: ByteArray
    get() = "bluke-mirror v1".toByteArray(Charsets.US_ASCII)

  /** RFC 9382's M for P-256. */
  internal val M =
      P256.decode(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))!!

  /** RFC 9382's N for P-256. */
  internal val N =
      P256.decode(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))!!

  private val W_PREFIX = "bluke-mirror pairing v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

  /** w = SHA-512("bluke-mirror pairing v1" ‖ 0x00 ‖ six ASCII digits) mod n. [code] must be six digits. */
  fun codeToW(code: String): BigInteger {
    require(code.length == 6 && code.all { it in '0'..'9' }) { "a pairing code is six digits" }
    val digest = MessageDigest.getInstance("SHA-512").digest(W_PREFIX + code.toByteArray(Charsets.US_ASCII))
    return BigInteger(1, digest).mod(P256.n)
  }

  /** A scalar drawn uniformly from [1, n) by rejection sampling. */
  fun randomScalar(random: SecureRandom = SecureRandom()): BigInteger {
    while (true) {
      val candidate = BigInteger(256, random)
      if (candidate.signum() > 0 && candidate < P256.n) return candidate
    }
  }

  /** A six-digit code drawn uniformly from 000000-999999. */
  fun randomCode(random: SecureRandom = SecureRandom()): String =
      random.nextInt(1_000_000).toString().padStart(6, '0')

  private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

enum class Spake2Role {
  /** The companion: sends pA = x·G + w·M. */
  A,
  /** The phone: sends pB = y·G + w·N. */
  B,
}

class Spake2Exception(message: String) : Exception(message)

/**
 * One side of one SPAKE2 run. [scalar] is x for role A or y for role B, in [1, n): production draws
 * it with [Spake2.randomScalar]; tests inject the vectors'.
 */
class Spake2Party(
    private val role: Spake2Role,
    private val identityA: ByteArray,
    private val identityB: ByteArray,
    private val w: BigInteger,
    private val aad: ByteArray,
    private val scalar: BigInteger,
) {
  init {
    require(scalar.signum() > 0 && scalar < P256.n) { "scalar must be in [1, n)" }
    require(w.signum() >= 0 && w < P256.n) { "w must be in [0, n)" }
  }

  private val ownBlind = if (role == Spake2Role.A) Spake2.M else Spake2.N
  private val peerBlind = if (role == Spake2Role.A) Spake2.N else Spake2.M

  /** This side's share, a 65-byte uncompressed point. */
  val share: ByteArray =
      P256.encode(P256.add(P256.multiply(P256.G, scalar), P256.multiply(ownBlind, w)))

  /**
   * Computes K from [peerShare] and the confirmation keys. Throws [Spake2Exception] when the share
   * isn't a point on the curve (the identity has no 65-byte encoding, so it is refused here too)
   * or K is the identity.
   */
  fun finish(peerShare: ByteArray): Spake2Confirmation {
    if (peerShare.size != MIRROR_POINT_BYTES || peerShare[0] != 4.toByte()) {
      throw Spake2Exception("peer share is not an uncompressed point")
    }
    val peer = P256.decode(peerShare) ?: throw Spake2Exception("peer share is not on the curve")
    // Cofactor h = 1 for P-256.
    val k = P256.multiply(P256.add(peer, P256.negate(P256.multiply(peerBlind, w))), scalar)
    if (k.isInfinity) throw Spake2Exception("K is the identity")
    val pA = if (role == Spake2Role.A) share else peerShare
    val pB = if (role == Spake2Role.A) peerShare else share
    val tt =
        lengthPrefixed(identityA, identityB, pA, pB, P256.encode(k), P256.fieldBytes(w))
    val hash = MessageDigest.getInstance("SHA-256").digest(tt)
    val ka = hash.copyOfRange(16, 32)
    val kc = hkdfSha256(ka, "ConfirmationKeys".toByteArray(Charsets.US_ASCII) + aad, 32)
    val cA = hmacSha256(kc.copyOfRange(0, 16), tt)
    val cB = hmacSha256(kc.copyOfRange(16, 32), tt)
    return if (role == Spake2Role.A) Spake2Confirmation(cA, cB, tt) else Spake2Confirmation(cB, cA, tt)
  }

  private fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
    val buffer = ByteBuffer.allocate(parts.sumOf { 8 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
    for (part in parts) buffer.putLong(part.size.toLong()).put(part)
    return buffer.array()
  }

  private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
    // An empty salt is HashLen zero bytes (RFC 5869); HMAC pads keys with zeros, so it's the same key.
    val prk = hmacSha256(ByteArray(32), ikm)
    require(length <= 32)
    return hmacSha256(prk, info + byteArrayOf(1)).copyOf(length)
  }

  private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
      Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
      }
}

/** The confirmation MACs of a finished run: [ownMac] to send, the peer's to check. */
class Spake2Confirmation
internal constructor(
    private val own: ByteArray,
    private val expectedPeer: ByteArray,
    internal val transcript: ByteArray,
) {
  /** cA for role A, cB for role B. */
  val ownMac: ByteArray
    get() = own.copyOf()

  /** Constant-time comparison of the peer's MAC. */
  fun verifyPeer(mac: ByteArray): Boolean = MessageDigest.isEqual(mac, expectedPeer)
}
