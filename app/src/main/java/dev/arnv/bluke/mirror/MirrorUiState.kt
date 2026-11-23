package dev.arnv.bluke.mirror

/** Where Mirror stands, as the surface shows it. One value at a time; the controller derives it. */
sealed interface MirrorPhase {
    /** The surface isn't on screen: nothing listens. */
    data object Off : MirrorPhase

    /** No Wi-Fi network: there's no LAN address to listen on. */
    data object NoWifi : MirrorPhase

    /** Listening and advertising; [pairedName] is the pinned companion, or null when none is. */
    data class Waiting(val pairedName: String?) : MirrorPhase

    /** A companion asked to pair: the phone shows [code] for the user to type there. */
    data class Pairing(val code: String, val companionName: String, val attemptsLeft: Int) : MirrorPhase

    /** Three wrong codes this session: pairing stays shut until Mirror is reopened. */
    data object PairingLocked : MirrorPhase

    /**
     * A pinned companion is connected; [hasFrame] once a frame has been decoded. [displayName] is
     * the computer's display it streams, once it has said. [pictureOverdue] when it has been
     * connected [FIRST_PICTURE_WAIT_MS] with no picture: its capture failed, and its terminal says
     * why. [pairing] is a pairing request that arrived meanwhile, whose code shows over the picture.
     */
    data class Connected(
        val companionName: String,
        val displayName: String?,
        val hasFrame: Boolean,
        val pairing: Pairing? = null,
        val pictureOverdue: Boolean = false,
    ) : MirrorPhase

    /** Listening failed, with the reason in plain words. */
    data class Failed(val reason: String) : MirrorPhase
}

/** A picture is on screen and touches reach the computer: connected, and the first frame decoded. */
val MirrorPhase.isLive: Boolean
    get() = this is MirrorPhase.Connected && hasFrame

/**
 * What the session knows about the connected companion, for [mirrorPhase]; [connectedForMs] is how
 * long it has been connected.
 */
data class MirrorSessionFacts(
    val companionName: String,
    val displayName: String?,
    val hasFrame: Boolean,
    val connectedForMs: Long = 0,
)

/**
 * How long a connected companion may take to send its first picture. It sends one as soon as it
 * has captured the screen, so this long without one means its capture is failing.
 */
const val FIRST_PICTURE_WAIT_MS = 5_000L

/**
 * The phase rule: stopped, then a listening failure, then a connected session (carrying any
 * pairing code that shows meanwhile, so a request during streaming still shows its code), then
 * pairing, then the lock, then no network, else waiting.
 */
fun mirrorPhase(
    running: Boolean,
    listenError: String?,
    session: MirrorSessionFacts?,
    pairing: PhonePairingState,
    listening: Boolean,
    pinnedName: String?,
): MirrorPhase {
    val code = (pairing as? PhonePairingState.ShowingCode)?.let {
        MirrorPhase.Pairing(it.code, it.companionName, it.attemptsLeft)
    }
    return when {
        !running -> MirrorPhase.Off
        listenError != null -> MirrorPhase.Failed(listenError)
        session != null -> MirrorPhase.Connected(
            companionName = session.companionName,
            displayName = session.displayName,
            hasFrame = session.hasFrame,
            pairing = code,
            pictureOverdue = !session.hasFrame && session.connectedForMs >= FIRST_PICTURE_WAIT_MS,
        )
        code != null -> code
        pairing is PhonePairingState.Locked -> MirrorPhase.PairingLocked
        !listening -> MirrorPhase.NoWifi
        else -> MirrorPhase.Waiting(pinnedName)
    }
}

/** The pairing code to show now, on its own card or over the picture; null when none is asked for. */
val MirrorPhase.pairingRequest: MirrorPhase.Pairing?
    get() = this as? MirrorPhase.Pairing ?: (this as? MirrorPhase.Connected)?.pairing

/** A short-lived message the surface shows once, over whatever else is on screen. */
sealed interface MirrorNotice {
    data class Paired(val companionName: String) : MirrorNotice
    data class WrongCode(val attemptsLeft: Int) : MirrorNotice
    data object Forgotten : MirrorNotice
    data class Declined(val companionName: String) : MirrorNotice
    /** The first picture of a session arrived; the rail has room for only part of a long name. */
    data class Streaming(val companionName: String) : MirrorNotice
}

data class MirrorUiState(
    val phase: MirrorPhase = MirrorPhase.Off,
    /** The phone's Wi-Fi address, for `bluke-mirror pair --phone`; null when it has none. */
    val address: String? = null,
    val port: Int = MIRROR_PORT,
    val pairedName: String? = null,
    val fps: Int = 0,
    val latencyMs: Int? = null,
    /** Nothing has come from the connected companion for [MirrorRates.LINK_QUIET_MS]: the picture may be frozen. */
    val linkQuiet: Boolean = false,
    val touchMode: MirrorTouchMode = MirrorTouchMode.DIRECT,
    val notice: MirrorNotice? = null,
) {
    /** What to type on the computer when mDNS can't find the phone. */
    val phoneArgument: String?
        get() = address?.let { if (port == MIRROR_PORT) it else "$it:$port" }
}

/** A live picture whose link has gone quiet: it may be frozen, so the surface dims it and says so. */
val MirrorUiState.pictureStale: Boolean
    get() = phase.isLive && linkQuiet

/** The rail's link numbers, and the sentence TalkBack reads for them. */
data class MirrorReadout(val rate: String, val latency: String, val description: String)

/**
 * The readout rule. No rate until there's a picture; frames come only when the screen changes, so
 * a live link with nothing drawn this second reads "idle", not "0 fps". Once the link is quiet both
 * numbers blank, since the last latency no longer says anything about the link.
 */
fun MirrorUiState.readout(): MirrorReadout {
    val connected = phase as? MirrorPhase.Connected
        ?: return MirrorReadout("– fps", "– ms", "Not streaming")
    val name = connected.companionName
    return when {
        linkQuiet -> MirrorReadout("– fps", "– ms", "$name isn't answering")
        connected.pictureOverdue -> MirrorReadout(
            "– fps",
            latencyMs?.let { "$it ms" } ?: "– ms",
            "Connected to $name, no picture yet",
        )
        !connected.hasFrame -> MirrorReadout(
            "– fps",
            latencyMs?.let { "$it ms" } ?: "– ms",
            "Connected to $name, waiting for the first picture",
        )
        else -> {
            val rate = if (fps > 0) "$fps fps" else "idle"
            val latency = latencyMs?.let { "$it ms" } ?: "– ms"
            val spokenRate = if (fps > 0) rate else "screen unchanged"
            MirrorReadout(rate, latency, "Streaming from $name, $spokenRate, link latency $latency")
        }
    }
}

/** One touch pointer as the surface saw it, in the surface's pixels. */
data class MirrorTouchPoint(val id: Long, val x: Float, val y: Float, val down: Boolean, val wasDown: Boolean)

/**
 * Every pointer of one input event on the mirror image, with the fit the image was drawn at.
 * [cancelled] when the system took the touch away (ACTION_CANCEL): a lift that never clicks.
 * [eventNanos] is the event's own time on the uptime clock, for the rig's timing.
 */
data class MirrorTouchFrame(
    val points: List<MirrorTouchPoint>,
    val uptimeMillis: Long,
    val fit: MirrorFit,
    val cancelled: Boolean = false,
    val eventNanos: Long = uptimeMillis * 1_000_000,
)
