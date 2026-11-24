package dev.arnv.bluke.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPairingTest {
  private val phoneFp = ByteArray(32) { 0x11 }
  private val companionFp = ByteArray(32) { 0x22 }
  private val otherCompanionFp = ByteArray(32) { 0x33 }
  private val relayFp = ByteArray(32) { 0x44 }

  private var clockMs = 0L
  private val codes = ArrayDeque(listOf("123456", "654321", "000001", "999999", "424242", "777777"))
  private val pairing =
      PhonePairing(drawCode = { codes.removeFirst() }, drawScalar = { Spake2.randomScalar() }, now = { clockMs })

  /** The companion's side of one run, as a role-A party; returns the phone's final step and whether cB verified. */
  private class CompanionRun(
      val steps: List<PairingStep>,
      val cBVerified: Boolean,
  ) {
    val last get() = steps.last()
  }

  private fun runPairing(
      connection: Any,
      typedCode: String? = null,
      phoneSeesCompanion: ByteArray = companionFp,
      phoneSeesPhone: ByteArray = phoneFp,
      companionSeesCompanion: ByteArray = phoneSeesCompanion,
      companionSeesPhone: ByteArray = phoneSeesPhone,
      sendConfirmEvenIfCbFails: Boolean = true,
      name: String = "Mac",
  ): CompanionRun {
    val steps = mutableListOf<PairingStep>()
    fun send(m: MirrorMessage) = pairing.onMessage(connection, m, phoneSeesPhone, phoneSeesCompanion).also { steps += it }

    val first = send(MirrorMessage.PairRequest(name))
    if (first.reply !is MirrorMessage.PairCode) return CompanionRun(steps, false)
    val code = typedCode ?: (pairing.state as PhonePairingState.ShowingCode).code
    val party =
        Spake2Party(Spake2Role.A, companionSeesCompanion, companionSeesPhone, Spake2.codeToW(code), Spake2.AAD, Spake2.randomScalar())
    val reply = send(MirrorMessage.PakeShare(party.share)).reply as MirrorMessage.PakeReply
    val confirmation = party.finish(reply.pB)
    val cBVerified = confirmation.verifyPeer(reply.cB)
    if (!cBVerified && !sendConfirmEvenIfCbFails) {
      pairing.onClosed(connection)
      return CompanionRun(steps, false)
    }
    send(MirrorMessage.PakeConfirm(confirmation.ownMac))
    return CompanionRun(steps, cBVerified)
  }

  @Test
  fun theRightCodePairsAndPinsTheCompanion() {
    val run = runPairing("c1", name = "Rick's Mac")
    assertTrue(run.cBVerified)
    assertEquals(MirrorMessage.PairCode(3), run.steps[0].reply)
    assertFalse(run.steps[0].close)
    assertEquals(MirrorMessage.PairResult(MirrorMessage.PairResult.PAIRED), run.last.reply)
    assertTrue(run.last.close)
    assertEquals(PinnedCompanion(companionFp, "Rick's Mac"), run.last.pinned)
    assertArrayEquals(companionFp, run.last.pinned!!.fingerprint)
    assertEquals(PhonePairingState.Idle, pairing.state)
    assertTrue(run.steps.dropLast(1).all { it.pinned == null })
  }

  @Test
  fun aWrongCodeFailsBothSidesAndPinsNothing() {
    val run = runPairing("c1", typedCode = "111111")
    assertFalse(run.cBVerified)
    assertEquals(MirrorMessage.PairResult(MirrorMessage.PairResult.WRONG_CODE), run.last.reply)
    assertTrue(run.last.close)
    assertTrue(run.steps.all { it.pinned == null })
    assertEquals(PhonePairingState.Idle, pairing.state)
    // The code was discarded and an attempt used: the next request gets a fresh code and 2 attempts.
    assertEquals(MirrorMessage.PairCode(2), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
    assertEquals(PhonePairingState.ShowingCode("654321", "Mac", 2), pairing.state)
  }

  @Test
  fun aCompanionThatClosesAfterABadCbUsesAnAttempt() {
    val run = runPairing("c1", typedCode = "111111", sendConfirmEvenIfCbFails = false)
    assertTrue(run.steps.all { it.pinned == null })
    assertEquals(MirrorMessage.PairCode(2), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun threeStrikesLockTheSession() {
    val first = runPairing("c1", typedCode = "000000")
    assertEquals(MirrorMessage.PairResult(1), first.last.reply)
    val second = runPairing("c2", typedCode = "000000")
    assertEquals(MirrorMessage.PairResult(1), second.last.reply)
    val third = runPairing("c3", typedCode = "000000")
    assertEquals(MirrorMessage.PairResult(MirrorMessage.PairResult.LOCKED), third.last.reply)
    assertEquals(PhonePairingState.Locked, pairing.state)

    val after = pairing.onMessage("c4", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    assertEquals(MirrorMessage.PairResult(MirrorMessage.PairResult.LOCKED), after.reply)
    assertTrue(after.close)
    assertEquals(PhonePairingState.Locked, pairing.state)
    assertTrue(listOf(first, second, third).flatMap { it.steps }.all { it.pinned == null })
  }

  @Test
  fun rePairingAfterASuccessReplacesThePinnedCompanion() {
    val firstPin = runPairing("c1").last.pinned!!
    val secondPin = runPairing("c2", phoneSeesCompanion = otherCompanionFp, name = "Other").last.pinned!!
    assertEquals(PinnedCompanion(otherCompanionFp, "Other"), secondPin)
    // A success uses no attempt.
    assertEquals(MirrorMessage.PairCode(3), pairing.onMessage("c3", MirrorMessage.PairRequest("x"), phoneFp, companionFp).reply)

    val hello = MirrorMessage.Hello(1, "Mac")
    assertEquals(MirrorRoute.Session, MirrorConnectionPolicy.route(hello, otherCompanionFp, secondPin.fingerprint))
    assertEquals(
        MirrorRoute.Close(MirrorMessage.Refused(MirrorMessage.Refused.NOT_PAIRED)),
        MirrorConnectionPolicy.route(hello, firstPin.fingerprint, secondPin.fingerprint))
  }

  @Test
  fun aSecondConcurrentRequesterIsRefusedWithoutDisturbingTheFirst() {
    assertEquals(MirrorMessage.PairCode(3), pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
    val second = pairing.onMessage("c2", MirrorMessage.PairRequest("Intruder"), phoneFp, otherCompanionFp)
    assertEquals(MirrorMessage.PairResult(MirrorMessage.PairResult.UNAVAILABLE), second.reply)
    assertTrue(second.close)
    pairing.onClosed("c2")
    assertEquals(PhonePairingState.ShowingCode("123456", "Mac", 3), pairing.state)
    // A PakeShare from the other connection doesn't reach the first attempt either.
    val stray = pairing.onMessage("c2", MirrorMessage.PakeShare(ByteArray(65)), phoneFp, otherCompanionFp)
    assertEquals(PairingStep(close = true), stray)
    assertEquals(PhonePairingState.ShowingCode("123456", "Mac", 3), pairing.state)
  }

  @Test
  fun declineBeforeTheShareIsFreeAndAfterItIsAStrike() {
    assertNull(pairing.decline())
    pairing.onMessage("c1", MirrorMessage.PairRequest("Stranger"), phoneFp, companionFp)
    assertEquals("c1", pairing.decline())
    assertEquals(PhonePairingState.Idle, pairing.state)
    // Its close afterwards is not a strike either.
    pairing.onClosed("c1")
    assertEquals(3, pairing.attemptsLeft)
    // Declining after a share costs an attempt: the PakeReply already let the sender check its guess.
    clockMs += PhonePairing.DECLINE_COOLDOWN_MS
    pairing.onMessage("c2", MirrorMessage.PairRequest("Stranger"), phoneFp, companionFp)
    val party = Spake2Party(Spake2Role.A, companionFp, phoneFp, Spake2.codeToW("000000"), Spake2.AAD, Spake2.randomScalar())
    pairing.onMessage("c2", MirrorMessage.PakeShare(party.share), phoneFp, companionFp)
    assertEquals("c2", pairing.decline())
    pairing.onClosed("c2")
    assertEquals(2, pairing.attemptsLeft)
    clockMs += PhonePairing.DECLINE_COOLDOWN_MS
    assertEquals(
        MirrorMessage.PairCode(2),
        pairing.onMessage("c3", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply,
    )
  }

  @Test
  fun aDeclineRefusesNewRequestsForAMinuteWithoutUsingAttempts() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Stranger"), phoneFp, companionFp)
    clockMs = 1_000
    pairing.decline()
    pairing.onClosed("c1")
    // Raised again at once, and just before the minute is up: refused, nothing shown, no try used.
    val unavailable = PairingStep(MirrorMessage.PairResult(MirrorMessage.PairResult.UNAVAILABLE), close = true)
    assertEquals(unavailable, pairing.onMessage("c2", MirrorMessage.PairRequest("Stranger"), phoneFp, companionFp))
    clockMs = 1_000 + PhonePairing.DECLINE_COOLDOWN_MS - 1
    assertEquals(unavailable, pairing.onMessage("c3", MirrorMessage.PairRequest("Stranger"), phoneFp, companionFp))
    assertEquals(PhonePairingState.Idle, pairing.state)
    assertEquals(3, pairing.attemptsLeft)
    // A minute on, a request shows its code again.
    clockMs = 1_000 + PhonePairing.DECLINE_COOLDOWN_MS
    assertEquals(
        MirrorMessage.PairCode(3),
        pairing.onMessage("c4", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply,
    )
    // Declining with nothing showing starts no cooldown.
    pairing.decline()
    clockMs += PhonePairing.DECLINE_COOLDOWN_MS
    assertNull(pairing.decline())
    assertEquals(
        MirrorMessage.PairCode(3),
        pairing.onMessage("c5", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply,
    )
  }

  @Test
  fun aCodeExpiresAfter120sWithoutAStrike() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    clockMs = 119_999
    assertNull(pairing.expire())
    assertTrue(pairing.state is PhonePairingState.ShowingCode)
    clockMs = 120_000
    assertEquals(PhonePairingState.Idle, pairing.state)
    assertEquals("c1", pairing.expire())
    assertNull(pairing.expire())
    // Its share arriving late is closed, and a new request still has all three attempts.
    assertEquals(PairingStep(close = true), pairing.onMessage("c1", MirrorMessage.PakeShare(ByteArray(65)), phoneFp, companionFp))
    assertEquals(MirrorMessage.PairCode(3), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun aCodeThatGotItsShareOutlivesTheCodeLifetimeUntilItsConfirmDeadline() {
    clockMs = 110_000
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    val party = Spake2Party(Spake2Role.A, companionFp, phoneFp, Spake2.codeToW("123456"), Spake2.AAD, Spake2.randomScalar())
    clockMs = 229_000
    val reply = pairing.onMessage("c1", MirrorMessage.PakeShare(party.share), phoneFp, companionFp).reply as MirrorMessage.PakeReply
    clockMs = 229_000 + PhonePairing.CONFIRM_LIFETIME_MS - 1
    assertNull(pairing.expire())
    val done = pairing.onMessage("c1", MirrorMessage.PakeConfirm(party.finish(reply.pB).ownMac), phoneFp, companionFp)
    assertEquals(PinnedCompanion(companionFp, "Mac"), done.pinned)
  }

  @Test
  fun aCloseBeforeTheShareIsFreeAndAfterItIsAStrike() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    pairing.onClosed("c1")
    assertEquals(PhonePairingState.Idle, pairing.state)
    assertEquals(MirrorMessage.PairCode(3), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
    val party = Spake2Party(Spake2Role.A, companionFp, phoneFp, Spake2.codeToW("654321"), Spake2.AAD, Spake2.randomScalar())
    pairing.onMessage("c2", MirrorMessage.PakeShare(party.share), phoneFp, companionFp)
    pairing.onClosed("c2")
    assertEquals(MirrorMessage.PairCode(2), pairing.onMessage("c3", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun anUnexpectedMessageAfterTheShareIsAStrike() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    val party = Spake2Party(Spake2Role.A, companionFp, phoneFp, Spake2.codeToW("123456"), Spake2.AAD, Spake2.randomScalar())
    pairing.onMessage("c1", MirrorMessage.PakeShare(party.share), phoneFp, companionFp)
    val step = pairing.onMessage("c1", MirrorMessage.Hello(1, "Mac"), phoneFp, companionFp)
    assertEquals(PairingStep(close = true), step)
    assertEquals(MirrorMessage.PairCode(2), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun anUnexpectedMessageBeforeTheShareClosesWithoutAStrike() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    assertEquals(PairingStep(close = true), pairing.onMessage("c1", MirrorMessage.Unknown(0x7f), phoneFp, companionFp))
    assertEquals(MirrorMessage.PairCode(3), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun aShareOffTheCurveIsAStrike() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    val bad = ByteArray(65).also { it[0] = 4 }
    val step = pairing.onMessage("c1", MirrorMessage.PakeShare(bad), phoneFp, companionFp)
    assertTrue(step.close)
    assertNull(step.pinned)
    assertEquals(MirrorMessage.PairCode(2), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun aRelayWithItsOwnCertificatesEndsUnpinned() {
    // The relay terminates both TLS sessions: the phone sees the relay as the companion, the
    // companion sees the relay as the phone. The right code still fails on both MACs.
    val run =
        runPairing(
            "c1",
            phoneSeesCompanion = relayFp,
            phoneSeesPhone = phoneFp,
            companionSeesCompanion = companionFp,
            companionSeesPhone = relayFp,
        )
    assertFalse(run.cBVerified)
    assertEquals(MirrorMessage.PairResult(MirrorMessage.PairResult.WRONG_CODE), run.last.reply)
    assertTrue(run.steps.all { it.pinned == null })
  }

  @Test
  fun aRunWithMatchingFingerprintsOnBothSidesPinsExactlyThatCompanion() {
    val run = runPairing("c1", companionSeesCompanion = companionFp, companionSeesPhone = phoneFp)
    assertTrue(run.cBVerified)
    assertArrayEquals(companionFp, run.last.pinned!!.fingerprint)
  }

  @Test
  fun connectionPolicyRoutesTheFirstMessage() {
    val hello = MirrorMessage.Hello(1, "Mac")
    val refused = MirrorRoute.Close(MirrorMessage.Refused(MirrorMessage.Refused.NOT_PAIRED))
    assertEquals(MirrorRoute.Session, MirrorConnectionPolicy.route(hello, companionFp, companionFp.copyOf()))
    assertEquals(refused, MirrorConnectionPolicy.route(hello, companionFp, null))
    assertEquals(refused, MirrorConnectionPolicy.route(hello, otherCompanionFp, companionFp))
    assertEquals(
        MirrorRoute.Close(MirrorMessage.Refused(MirrorMessage.Refused.UNSUPPORTED_VERSION)),
        MirrorConnectionPolicy.route(MirrorMessage.Hello(2, "Mac"), companionFp, companionFp))
    assertEquals(refused, MirrorConnectionPolicy.route(MirrorMessage.Hello(2, "Mac"), otherCompanionFp, companionFp))
    assertEquals(MirrorRoute.Pairing, MirrorConnectionPolicy.route(MirrorMessage.PairRequest("Mac"), otherCompanionFp, companionFp))
    assertEquals(MirrorRoute.Pairing, MirrorConnectionPolicy.route(MirrorMessage.PairRequest("Mac"), companionFp, null))
    for (first in listOf(
        MirrorMessage.Display(1, 10, 10, "d"),
        MirrorMessage.Frame(1, 1, 1, 1, 0, byteArrayOf(1)),
        MirrorMessage.PakeShare(ByteArray(65)),
        MirrorMessage.Pong(1),
        MirrorMessage.Unknown(0x7f),
    )) {
      assertEquals(first.toString(), MirrorRoute.Close(null), MirrorConnectionPolicy.route(first, companionFp, companionFp))
    }
  }

  @Test
  fun aSessionHandlesCompanionTrafficSkipsUnknownAndClosesOnTheRest() {
    assertEquals(SessionVerdict.HANDLE, MirrorConnectionPolicy.sessionAccepts(MirrorMessage.Display(1, 1512, 982, "Built-in")))
    assertEquals(SessionVerdict.HANDLE, MirrorConnectionPolicy.sessionAccepts(MirrorMessage.Frame(1, 1, 2, 2, 0, byteArrayOf(1))))
    assertEquals(SessionVerdict.HANDLE, MirrorConnectionPolicy.sessionAccepts(MirrorMessage.Pong(5)))
    assertEquals(SessionVerdict.SKIP, MirrorConnectionPolicy.sessionAccepts(MirrorMessage.Unknown(0x7f)))
    for (m in listOf(
        MirrorMessage.Hello(1, "Mac"),
        MirrorMessage.PairRequest("Mac"),
        MirrorMessage.PakeConfirm(ByteArray(32)),
        MirrorMessage.Welcome(),
        MirrorMessage.Pointer(0, 0, 0),
        MirrorMessage.Ping(1),
        MirrorMessage.FrameAck(1, 1),
        MirrorMessage.Release,
    )) {
      assertEquals(m.toString(), SessionVerdict.CLOSE, MirrorConnectionPolicy.sessionAccepts(m))
    }
  }

  @Test
  fun aFullListenerMakesWayForANewConnectionOnlyByDroppingTheOldestUnrouted() {
    assertEquals(MirrorAdmission.Admit, MirrorConnectionPolicy.admit(open = 3, unroutedOldestFirst = listOf("a"), max = 4))
    assertEquals(MirrorAdmission.Evict("a"), MirrorConnectionPolicy.admit(open = 4, unroutedOldestFirst = listOf("a", "b"), max = 4))
    // Every open connection is a session or a pairing: the newcomer is turned away.
    assertEquals(MirrorAdmission.Refuse, MirrorConnectionPolicy.admit(open = 4, unroutedOldestFirst = emptyList<String>(), max = 4))
  }

  @Test
  fun aConfirmHeldPastItsDeadlineEndsTheAttemptWithAStrike() {
    pairing.onMessage("c1", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp)
    val party = Spake2Party(Spake2Role.A, companionFp, phoneFp, Spake2.codeToW("123456"), Spake2.AAD, Spake2.randomScalar())
    pairing.onMessage("c1", MirrorMessage.PakeShare(party.share), phoneFp, companionFp)
    clockMs = PhonePairing.CONFIRM_LIFETIME_MS
    assertEquals("c1", pairing.expire())
    assertEquals(2, pairing.attemptsLeft)
    assertEquals(PhonePairingState.Idle, pairing.state)
    // Its close afterwards costs nothing more, and the slot is free for the next request.
    pairing.onClosed("c1")
    assertEquals(MirrorMessage.PairCode(2), pairing.onMessage("c2", MirrorMessage.PairRequest("Mac"), phoneFp, companionFp).reply)
  }

  @Test
  fun aConnectionMustSendItsFirstMessageWithinOneDeadlineFromAccept() {
    val accepted = linkedMapOf("old" to 0L, "new" to 5_000L)
    assertEquals(emptyList<String>(), MirrorConnectionPolicy.pastFirstMessageDeadline(accepted, 9_999))
    assertEquals(listOf("old"), MirrorConnectionPolicy.pastFirstMessageDeadline(accepted, MirrorConnectionPolicy.FIRST_MESSAGE_DEADLINE_MS))
    assertEquals(listOf("old", "new"), MirrorConnectionPolicy.pastFirstMessageDeadline(accepted, 15_000))
  }
}
