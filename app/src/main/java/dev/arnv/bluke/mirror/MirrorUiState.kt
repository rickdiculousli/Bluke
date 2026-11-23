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

    /** A pinned companion is connected; [hasFrame] once a frame has been decoded. */
    data class Connected(val companionName: String, val displayName: String?, val hasFrame: Boolean) : MirrorPhase

    /** Listening failed, with the reason in plain words. */
    data class Failed(val reason: String) : MirrorPhase
}

/** A picture is on screen and touches reach the computer: connected, and the first frame decoded. */
val MirrorPhase.isLive: Boolean
    get() = this is MirrorPhase.Connected && hasFrame

/** A short-lived message the surface shows once, over whatever else is on screen. */
sealed interface MirrorNotice {
    data class Paired(val companionName: String) : MirrorNotice
    data class WrongCode(val attemptsLeft: Int) : MirrorNotice
    data object Forgotten : MirrorNotice
}

data class MirrorUiState(
    val phase: MirrorPhase = MirrorPhase.Off,
    /** The phone's Wi-Fi address, for `bluke-mirror pair --phone`; null when it has none. */
    val address: String? = null,
    val port: Int = MIRROR_PORT,
    val pairedName: String? = null,
    val fps: Int = 0,
    val latencyMs: Int? = null,
    val touchMode: MirrorTouchMode = MirrorTouchMode.DIRECT,
    val notice: MirrorNotice? = null,
) {
    /** What to type on the computer when mDNS can't find the phone. */
    val phoneArgument: String?
        get() = address?.let { if (port == MIRROR_PORT) it else "$it:$port" }
}

/** One touch pointer as the surface saw it, in the surface's pixels. */
data class MirrorTouchPoint(val id: Long, val x: Float, val y: Float, val down: Boolean, val wasDown: Boolean)

/** Every pointer of one input event on the mirror image, with the fit the image was drawn at. */
data class MirrorTouchFrame(val points: List<MirrorTouchPoint>, val uptimeMillis: Long, val fit: MirrorFit)
