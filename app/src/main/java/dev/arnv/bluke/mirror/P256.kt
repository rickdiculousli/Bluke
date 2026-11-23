package dev.arnv.bluke.mirror

import java.math.BigInteger

/**
 * Just enough NIST P-256 for SPAKE2: Jacobian points, a fixed-length Montgomery ladder, and SEC1
 * point encoding. Built on [BigInteger], which is not constant-time; see [Spake2] for why that is
 * acceptable here.
 */
internal object P256 {
  val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
  val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
  private val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
  private val three = BigInteger.valueOf(3)

  val G =
      JacobianPoint(
          BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
          BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
          BigInteger.ONE,
      )

  val INFINITY = JacobianPoint(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

  /** A point in Jacobian coordinates (x = X/Z², y = Y/Z³); Z = 0 is the identity. */
  class JacobianPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
    val isInfinity: Boolean
      get() = z.signum() == 0
  }

  fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
    if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
    val lhs = y.multiply(y).mod(p)
    val rhs = x.pow(3).subtract(x.multiply(three)).add(b).mod(p)
    return lhs == rhs
  }

  fun negate(q: JacobianPoint): JacobianPoint =
      if (q.isInfinity) q else JacobianPoint(q.x, p.subtract(q.y).mod(p), q.z)

  fun double(q: JacobianPoint): JacobianPoint {
    if (q.isInfinity || q.y.signum() == 0) return INFINITY
    val delta = q.z.multiply(q.z).mod(p)
    val gamma = q.y.multiply(q.y).mod(p)
    val beta = q.x.multiply(gamma).mod(p)
    val alpha = three.multiply(q.x.subtract(delta)).multiply(q.x.add(delta)).mod(p)
    val x3 = alpha.multiply(alpha).subtract(beta.shiftLeft(3)).mod(p)
    val z3 = q.y.add(q.z).pow(2).subtract(gamma).subtract(delta).mod(p)
    val y3 =
        alpha.multiply(beta.shiftLeft(2).subtract(x3)).subtract(gamma.multiply(gamma).shiftLeft(3)).mod(p)
    return JacobianPoint(x3, y3, z3)
  }

  fun add(a: JacobianPoint, c: JacobianPoint): JacobianPoint {
    if (a.isInfinity) return c
    if (c.isInfinity) return a
    val z1z1 = a.z.multiply(a.z).mod(p)
    val z2z2 = c.z.multiply(c.z).mod(p)
    val u1 = a.x.multiply(z2z2).mod(p)
    val u2 = c.x.multiply(z1z1).mod(p)
    val s1 = a.y.multiply(c.z).multiply(z2z2).mod(p)
    val s2 = c.y.multiply(a.z).multiply(z1z1).mod(p)
    val h = u2.subtract(u1).mod(p)
    val r = s2.subtract(s1).mod(p)
    if (h.signum() == 0) return if (r.signum() == 0) double(a) else INFINITY
    val hh = h.multiply(h).mod(p)
    val hhh = h.multiply(hh).mod(p)
    val v = u1.multiply(hh).mod(p)
    val x3 = r.multiply(r).subtract(hhh).subtract(v.shiftLeft(1)).mod(p)
    val y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(hhh)).mod(p)
    val z3 = a.z.multiply(c.z).multiply(h).mod(p)
    return JacobianPoint(x3, y3, z3)
  }

  /**
   * [k]·[q] by a Montgomery ladder that always walks all 256 bit positions and does one add and
   * one double per bit, whatever the scalar's bits are.
   */
  fun multiply(q: JacobianPoint, k: BigInteger): JacobianPoint {
    var r0 = INFINITY
    var r1 = q
    for (i in 255 downTo 0) {
      if (k.testBit(i)) {
        r0 = add(r0, r1)
        r1 = double(r1)
      } else {
        r1 = add(r0, r1)
        r0 = double(r0)
      }
    }
    return r0
  }

  /** SEC1 uncompressed encoding (65 bytes). The identity has no such encoding. */
  fun encode(q: JacobianPoint): ByteArray {
    check(!q.isInfinity) { "the identity has no uncompressed encoding" }
    val zInv = q.z.modInverse(p)
    val zInv2 = zInv.multiply(zInv).mod(p)
    val x = q.x.multiply(zInv2).mod(p)
    val y = q.y.multiply(zInv2).multiply(zInv).mod(p)
    return byteArrayOf(4) + fieldBytes(x) + fieldBytes(y)
  }

  /** Decodes a SEC1 uncompressed (65 bytes) or compressed (33 bytes) point; null if it isn't one on the curve. */
  fun decode(bytes: ByteArray): JacobianPoint? {
    when {
      bytes.size == 65 && bytes[0] == 4.toByte() -> {
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return if (isOnCurve(x, y)) JacobianPoint(x, y, BigInteger.ONE) else null
      }
      bytes.size == 33 && (bytes[0] == 2.toByte() || bytes[0] == 3.toByte()) -> {
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        if (x >= p) return null
        val rhs = x.pow(3).subtract(x.multiply(three)).add(b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y.multiply(y).mod(p) != rhs) return null
        if (y.testBit(0) != (bytes[0] == 3.toByte())) y = p.subtract(y).mod(p)
        return JacobianPoint(x, y, BigInteger.ONE)
      }
      else -> return null
    }
  }

  /** [v] as exactly 32 big-endian bytes. */
  fun fieldBytes(v: BigInteger): ByteArray {
    val raw = v.toByteArray()
    return when {
      raw.size == 32 -> raw
      raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
      else -> ByteArray(32 - raw.size) + raw
    }
  }
}
