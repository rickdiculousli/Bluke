package dev.arnv.bluke.mirror

import java.io.File
import java.math.BigInteger
import org.json.JSONObject

/** The companion's shared vectors, read from `companion/vectors/` at test time (unit tests run in `app/`). */
object MirrorVectors {
  val protocol: JSONObject by lazy { load("protocol.json") }
  val spake2: JSONObject by lazy { load("spake2.json") }

  private fun load(name: String) = JSONObject(File("../companion/vectors/$name").readText())

  fun hex(s: String): ByteArray {
    val clean = s.filterNot { it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
  }

  fun big(s: String) = BigInteger(1, hex(s))
}
