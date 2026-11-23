package dev.arnv.bluke.mirror

import java.math.BigInteger
import java.security.MessageDigest

/** What the Mirror surface shows about pairing. */
sealed interface PhonePairingState {
  data object Idle : PhonePairingState

  /** A companion asked to pair: show [code] for the user to type at [companionName]. */
  data class ShowingCode(val code: String, val companionName: String, val attemptsLeft: Int) :
      PhonePairingState

  /** This Mirror session's attempts are used up; it unlocks when the surface leaves the screen. */
  data object Locked : PhonePairingState
}

/** A companion confirmed by a pairing run, to persist as the pinned one (replacing any earlier). */
class PinnedCompanion(fingerprint: ByteArray, val name: String) {
  private val fp = fingerprint.copyOf()
  val fingerprint: ByteArray
    get() = fp.copyOf()

  override fun equals(other: Any?) =
      other is PinnedCompanion && fp.contentEquals(other.fp) && name == other.name
  override fun hashCode() = 31 * fp.contentHashCode() + name.hashCode()
  override fun toString() = "PinnedCompanion(fingerprint=${fp.toHex()}, name=$name)"
}

/**
 * What to do after a pairing message: send [reply] (if any), then close the connection if [close],
 * and persist [pinned] (set only once `cA` verified).
 */
data class PairingStep(
    val reply: MirrorMessage? = null,
    val close: Boolean = false,
    val pinned: PinnedCompanion? = null,
)

/**
 * The phone's side of pairing (`companion/PROTOCOL.md` "Pairing"), without sockets or clocks.
 * One instance lives for one Mirror session (surface visible → gone), so its attempts reset with
 * the next session. It holds at most one pairing connection at a time; connections are opaque
 * tokens compared with `==`.
 *
 * The caller feeds every message read on a connection routed to pairing to [onMessage], calls
 * [onClosed] when such a connection closes or a read on it fails (a malformed message, a reset),
 * and calls [expire] about once a second, closing the connection it returns.
 *
 * @param drawCode a fresh six-digit code from a secure source ([Spake2.randomCode]).
 * @param drawScalar a fresh y in [1, n) ([Spake2.randomScalar]).
 * @param now a monotonic clock in milliseconds.
 */
class PhonePairing(
    private val drawCode: () -> String,
    private val drawScalar: () -> BigInteger,
    private val now: () -> Long,
    maxAttempts: Int = 3,
) {
  private class Attempt(
      val connection: Any,
      val code: String,
      val companionName: String,
      val startedAtMs: Long,
  ) {
    /** Set once a PakeShare arrived: from then on, any end but PairResult(0) is a strike. */
    var awaitingConfirm: Spake2Confirmation? = null
    var companionFingerprint: ByteArray? = null
  }

  private var attemptsLeft = maxAttempts
  private var attempt: Attempt? = null

  /** What the UI shows now. A code past its 120 s already reads as [PhonePairingState.Idle]. */
  val state: PhonePairingState
    get() {
      if (attemptsLeft <= 0) return PhonePairingState.Locked
      val a = attempt ?: return PhonePairingState.Idle
      if (isExpired(a)) return PhonePairingState.Idle
      return PhonePairingState.ShowingCode(a.code, a.companionName, attemptsLeft)
    }

  /**
   * Handles one pairing message read on [connection]. [phoneFingerprint] and
   * [companionFingerprint] are SHA-256 of the phone's and the companion's certificates as this
   * connection's TLS session sees them; they become SPAKE2's B and A identities.
   */
  fun onMessage(
      connection: Any,
      message: MirrorMessage,
      phoneFingerprint: ByteArray,
      companionFingerprint: ByteArray,
  ): PairingStep {
    expire()
    val current = attempt
    if (current == null || current.connection != connection) {
      return when {
        message !is MirrorMessage.PairRequest -> PairingStep(close = true)
        attemptsLeft <= 0 -> PairingStep(MirrorMessage.PairResult(MirrorMessage.PairResult.LOCKED), close = true)
        current != null ->
            PairingStep(MirrorMessage.PairResult(MirrorMessage.PairResult.UNAVAILABLE), close = true)
        else -> {
          attempt = Attempt(connection, drawCode(), message.name, now())
          PairingStep(MirrorMessage.PairCode(attemptsLeft))
        }
      }
    }

    val confirmation = current.awaitingConfirm
    return when {
      confirmation == null && message is MirrorMessage.PakeShare -> {
        val party =
            Spake2Party(
                Spake2Role.B,
                identityA = companionFingerprint,
                identityB = phoneFingerprint,
                w = Spake2.codeToW(current.code),
                aad = Spake2.AAD,
                scalar = drawScalar(),
            )
        val finished =
            try {
              party.finish(message.pA)
            } catch (_: Spake2Exception) {
              return fail(strike = true, reply = false)
            }
        current.awaitingConfirm = finished
        current.companionFingerprint = companionFingerprint.copyOf()
        PairingStep(MirrorMessage.PakeReply(party.share, finished.ownMac))
      }
      confirmation != null && message is MirrorMessage.PakeConfirm -> {
        val sameCompanion =
            MessageDigest.isEqual(current.companionFingerprint, companionFingerprint)
        if (sameCompanion && confirmation.verifyPeer(message.cA)) {
          attempt = null
          PairingStep(
              MirrorMessage.PairResult(MirrorMessage.PairResult.PAIRED),
              close = true,
              pinned = PinnedCompanion(companionFingerprint, current.companionName),
          )
        } else {
          fail(strike = true, reply = true)
        }
      }
      else -> fail(strike = confirmation != null, reply = false)
    }
  }

  /** [connection] closed or failed to read. A close after a PakeShare uses an attempt. */
  fun onClosed(connection: Any) {
    val current = attempt ?: return
    if (current.connection != connection) return
    fail(strike = current.awaitingConfirm != null, reply = false)
  }

  /**
   * Discards a code that got no PakeShare within 120 s, without using an attempt. Returns the
   * connection to close, or null.
   */
  fun expire(): Any? {
    val current = attempt ?: return null
    if (!isExpired(current)) return null
    attempt = null
    return current.connection
  }

  private fun isExpired(a: Attempt) =
      a.awaitingConfirm == null && now() - a.startedAtMs >= CODE_LIFETIME_MS

  private fun fail(strike: Boolean, reply: Boolean): PairingStep {
    attempt = null
    if (strike) attemptsLeft = (attemptsLeft - 1).coerceAtLeast(0)
    val result =
        if (attemptsLeft <= 0) MirrorMessage.PairResult.LOCKED else MirrorMessage.PairResult.WRONG_CODE
    return PairingStep(if (reply) MirrorMessage.PairResult(result) else null, close = true)
  }

  companion object {
    const val CODE_LIFETIME_MS = 120_000L
  }
}

/** Where a connection's first message sends it. */
sealed interface MirrorRoute {
  /** Hand this and every later message to [PhonePairing]. */
  data object Pairing : MirrorRoute

  /** The pinned companion said Hello: answer Welcome and run the session. */
  data object Session : MirrorRoute

  /** Send [reply] if any, then close. */
  data class Close(val reply: MirrorMessage?) : MirrorRoute
}

/** What a verified session does with a message it read. */
enum class SessionVerdict {
  HANDLE,
  SKIP,
  CLOSE,
}

/** Which connections and messages the phone accepts (`companion/PROTOCOL.md` "TLS and identity"). */
object MirrorConnectionPolicy {
  /**
   * Routes a connection by its first message. A PairRequest goes to pairing whatever is pinned
   * (re-pairing replaces the pin). A Hello reaches a session only from the pinned companion, else
   * `Refused(1)`; a pinned companion speaking another version gets `Refused(2)`. Anything else
   * closes without a reply.
   */
  fun route(
      first: MirrorMessage,
      companionFingerprint: ByteArray,
      pinnedFingerprint: ByteArray?,
  ): MirrorRoute =
      when (first) {
        is MirrorMessage.PairRequest -> MirrorRoute.Pairing
        is MirrorMessage.Hello ->
            when {
              pinnedFingerprint == null ||
                  !MessageDigest.isEqual(companionFingerprint, pinnedFingerprint) ->
                  MirrorRoute.Close(MirrorMessage.Refused(MirrorMessage.Refused.NOT_PAIRED))
              first.version != MIRROR_PROTOCOL_VERSION ->
                  MirrorRoute.Close(MirrorMessage.Refused(MirrorMessage.Refused.UNSUPPORTED_VERSION))
              else -> MirrorRoute.Session
            }
        else -> MirrorRoute.Close(null)
      }

  /** A verified session handles the companion's session traffic, skips Unknown, and closes on anything else. */
  fun sessionAccepts(message: MirrorMessage): SessionVerdict =
      when (message) {
        is MirrorMessage.Display,
        is MirrorMessage.Frame,
        is MirrorMessage.Pong -> SessionVerdict.HANDLE
        is MirrorMessage.Unknown -> SessionVerdict.SKIP
        else -> SessionVerdict.CLOSE
      }
}
