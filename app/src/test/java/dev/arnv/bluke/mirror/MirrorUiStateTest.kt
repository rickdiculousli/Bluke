package dev.arnv.bluke.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorUiStateTest {
  private val session = MirrorSessionFacts("Mac", "Built-in Display", hasFrame = true)
  private val showing = PhonePairingState.ShowingCode("482913", "Stranger", attemptsLeft = 3)

  private fun phase(
      running: Boolean = true,
      listenError: String? = null,
      session: MirrorSessionFacts? = null,
      pairing: PhonePairingState = PhonePairingState.Idle,
      listening: Boolean = true,
      pinnedName: String? = "Mac",
  ) = mirrorPhase(running, listenError, session, pairing, listening, pinnedName)

  @Test
  fun thePhaseRuleRanksEachInputInOrder() {
    assertEquals(MirrorPhase.Off, phase(running = false, listenError = "x", session = session, pairing = showing))
    assertEquals(MirrorPhase.Failed("x"), phase(listenError = "x", session = session, pairing = showing))
    assertEquals(MirrorPhase.Connected("Mac", "Built-in Display", hasFrame = true), phase(session = session))
    assertEquals(MirrorPhase.Pairing("482913", "Stranger", 3), phase(pairing = showing, listening = false))
    assertEquals(MirrorPhase.PairingLocked, phase(pairing = PhonePairingState.Locked, listening = false))
    assertEquals(MirrorPhase.NoWifi, phase(listening = false))
    assertEquals(MirrorPhase.Waiting("Mac"), phase())
    assertEquals(MirrorPhase.Waiting(null), phase(pinnedName = null))
  }

  @Test
  fun aPairingRequestDuringASessionKeepsStreamingAndCarriesItsCode() {
    val p = phase(session = session, pairing = showing)
    assertEquals(MirrorPhase.Connected("Mac", "Built-in Display", true, MirrorPhase.Pairing("482913", "Stranger", 3)), p)
    assertTrue(p.isLive)
    assertEquals(MirrorPhase.Pairing("482913", "Stranger", 3), p.pairingRequest)
    // The lock doesn't interrupt a session.
    assertEquals(MirrorPhase.Connected("Mac", "Built-in Display", true), phase(session = session, pairing = PhonePairingState.Locked))
    assertNull(phase(session = session).pairingRequest)
    assertEquals(MirrorPhase.Pairing("482913", "Stranger", 3), phase(pairing = showing).pairingRequest)
  }

  @Test
  fun aRequestOverALivePictureIsAChipUntilItsCodeIsAskedFor() {
    val live = phase(session = session, pairing = showing)
    assertEquals(PairingPrompt.CHIP, live.pairingPrompt(shownCode = null))
    assertEquals(PairingPrompt.CARD, live.pairingPrompt(shownCode = "482913"))
    // Asking to see an earlier request's code doesn't open a new one.
    assertEquals(PairingPrompt.CHIP, live.pairingPrompt(shownCode = "111111"))
    // With no picture to keep touchable, the code shows on the card at once.
    assertEquals(PairingPrompt.CARD, phase(session = session.copy(hasFrame = false), pairing = showing).pairingPrompt(null))
    assertEquals(PairingPrompt.CARD, phase(pairing = showing).pairingPrompt(null))
    assertEquals(PairingPrompt.NONE, phase(session = session).pairingPrompt(null))
    assertEquals(PairingPrompt.NONE, phase().pairingPrompt("482913"))
  }

  private val streaming = MirrorUiState(phase = MirrorPhase.Connected("Mac", null, hasFrame = true), fps = 20, latencyMs = 6)

  @Test
  fun theReadoutShowsTheRateAndLatencyWhileFrameAreDrawn() {
    assertEquals(MirrorReadout("20 fps", "6 ms", "Streaming from Mac, 20 fps, link latency 6 ms"), streaming.readout())
  }

  @Test
  fun anUnchangedScreenReadsIdleNotZero() {
    val idle = streaming.copy(fps = 0).readout()
    assertEquals("idle", idle.rate)
    assertEquals("6 ms", idle.latency)
    assertEquals("Streaming from Mac, screen unchanged, link latency 6 ms", idle.description)
  }

  @Test
  fun aQuietLinkBlanksBothNumbersAndDimsThePicture() {
    val quiet = streaming.copy(fps = 0, linkQuiet = true)
    assertEquals(MirrorReadout("– fps", "– ms", "Mac isn't answering"), quiet.readout())
    assertTrue(quiet.pictureStale)
    assertFalse(streaming.pictureStale)
    // Quiet without a picture isn't a stale picture.
    val connecting = MirrorUiState(phase = MirrorPhase.Connected("Mac", null, hasFrame = false), linkQuiet = true)
    assertFalse(connecting.pictureStale)
  }

  @Test
  fun noRateBeforeThePictureAndNothingWhenNotConnected() {
    val connecting = MirrorUiState(phase = MirrorPhase.Connected("Mac", null, hasFrame = false), latencyMs = 7)
    assertEquals(MirrorReadout("– fps", "7 ms", "Connected to Mac, waiting for the first picture"), connecting.readout())
    assertEquals(
        MirrorReadout("– fps", "– ms", "Not streaming"),
        MirrorUiState(phase = MirrorPhase.Waiting("Mac"), fps = 3, latencyMs = 5).readout(),
    )
  }

  @Test
  fun noPictureFiveSecondsAfterConnectingSaysToLookAtTheComputer() {
    val justConnected = MirrorSessionFacts("Mac", null, hasFrame = false, connectedForMs = FIRST_PICTURE_WAIT_MS - 1)
    assertEquals(false, (phase(session = justConnected) as MirrorPhase.Connected).pictureOverdue)
    val overdue = phase(session = justConnected.copy(connectedForMs = FIRST_PICTURE_WAIT_MS))
    assertEquals(true, (overdue as MirrorPhase.Connected).pictureOverdue)
    assertFalse(overdue.isLive)
    assertEquals(
        MirrorReadout("– fps", "4 ms", "Connected to Mac, no picture yet"),
        MirrorUiState(phase = overdue, latencyMs = 4).readout(),
    )
    // A picture that came, however late, is never overdue.
    val late = phase(session = justConnected.copy(hasFrame = true, connectedForMs = 60_000))
    assertEquals(false, (late as MirrorPhase.Connected).pictureOverdue)
    assertTrue(late.isLive)
  }
}
