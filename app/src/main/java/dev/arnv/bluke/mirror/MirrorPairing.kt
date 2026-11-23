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
    /** When the PakeShare arrived, for [CONFIRM_LIFETIME_MS]. */
    var sharedAtMs = 0L
    var companionFingerprint: ByteArray? = null
  }

  /** Attempts this Mirror session still allows. */
  var attemptsLeft = maxAttempts
    private set
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
        current.sharedAtMs = now()
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
   * Discards a code that got no PakeShare within [CODE_LIFETIME_MS], without using an attempt, and
   * ends an attempt whose PakeConfirm hasn't come [CONFIRM_LIFETIME_MS] after its PakeShare, using
   * one (a held confirm would otherwise keep the one pairing slot, and its guess, forever). Returns
   * the connection to close, or null.
   */
  fun expire(): Any? {
    val current = attempt ?: return null
    when {
      isExpired(current) -> attempt = null
      current.awaitingConfirm != null && now() - current.sharedAtMs >= CONFIRM_LIFETIME_MS ->
          fail(strike = true, reply = false)
      else -> return null
    }
    return current.connection
  }

  /**
   * The user declined the request on the phone. Before a PakeShare its code is discarded without
   * using an attempt, so a stranger's request can't eat into them; after one it uses an attempt
   * like any other end but pairing, since the PakeReply already let the sender check its guess.
   * Returns the connection to close, or null.
   */
  fun decline(): Any? {
    val current = attempt ?: return null
    fail(strike = current.awaitingConfirm != null, reply = false)
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
    /** How long after its PakeShare the companion has to send PakeConfirm. */
    const val CONFIRM_LIFETIME_MS = 15_000L
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

/** What the listener does with a new connection (see [MirrorConnectionPolicy.admit]). */
sealed interface MirrorAdmission<out T> {
  data object Admit : MirrorAdmission<Nothing>

  /** Close [oldest] (a connection that never said who it is), then admit the new one. */
  data class Evict<T>(val oldest: T) : MirrorAdmission<T>

  /** Every open connection is routed (a session, a pairing): close the new one. */
  data object Refuse : MirrorAdmission<Nothing>
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

  /**
   * Admits a new connection while fewer than [max] are open. At the cap, the oldest connection that
   * hasn't sent its first message yet ([unroutedOldestFirst]) makes way, so idle connections
   * (a scanner, a stuck handshake) can't lock the companion out; routed ones are never displaced.
   */
  fun <T> admit(open: Int, unroutedOldestFirst: List<T>, max: Int): MirrorAdmission<T> =
      when {
        open < max -> MirrorAdmission.Admit
        unroutedOldestFirst.isNotEmpty() -> MirrorAdmission.Evict(unroutedOldestFirst.first())
        else -> MirrorAdmission.Refuse
      }

  /**
   * The connections to close because they haven't finished the TLS handshake and sent their first
   * message within [FIRST_MESSAGE_DEADLINE_MS] of being accepted ([acceptedAtMs] by connection).
   * One deadline for both, so trickling bytes can't hold a connection slot by keeping each read
   * under its own timeout.
   */
  fun <T> pastFirstMessageDeadline(acceptedAtMs: Map<T, Long>, nowMs: Long): List<T> =
      acceptedAtMs.filterValues { nowMs - it >= FIRST_MESSAGE_DEADLINE_MS }.keys.toList()

  const val FIRST_MESSAGE_DEADLINE_MS = 10_000L

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
