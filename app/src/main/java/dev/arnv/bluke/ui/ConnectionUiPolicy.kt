package dev.arnv.bluke.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.ConnectionFailure
import dev.arnv.bluke.bluetooth.HidFailure
import dev.arnv.bluke.bluetooth.HidLifecycleState
import dev.arnv.bluke.bluetooth.LastConnectionFailure

/** An attempt that has been connecting or pairing this long is [ConnectionUi.Failed] with [FailReason.STALLED]. */
internal const val CONNECTION_STALL_MILLIS = 12_000L

/** Everything the manager publishes that decides what the connection looks like, as plain data. */
data class ConnectionInputs(
    val bluetoothState: BluetoothState,
    val lifecycle: HidLifecycleState,
    val connectedAddress: String? = null,
    val targetAddress: String? = null,
    val isScanning: Boolean = false,
    val isDisconnecting: Boolean = false,
    val lastFailure: LastConnectionFailure? = null,
    val reconnectAddress: String? = null,
    /** Hosts the phone knows (paired or found), by address, so a state can name its host. */
    val knownHosts: Map<String, HostRef> = emptyMap(),
    /** When the attempt in flight (outgoing or incoming) started, on the same clock as [now]. */
    val attemptStartedAt: Long? = null,
    val now: Long = 0L,
) {
    fun host(address: String): HostRef = knownHosts[address] ?: HostRef(address = address, name = null, bonded = false)
}

enum class BlockReason { NO_ADAPTER, NO_HID_ROLE, BLUETOOTH_OFF, PERMISSION_MISSING }

enum class FailReason { HOST_REFUSED, STALLED, SERVICE_FAILED }

/** What the connection is doing, in the words a person would use. Every manager state maps to exactly one. */
sealed interface ConnectionUi {
    data object Checking : ConnectionUi
    data class Blocked(val reason: BlockReason) : ConnectionUi
    data class Starting(val attempt: Int) : ConnectionUi
    data class Ready(val reconnectHost: HostRef?, val scanning: Boolean) : ConnectionUi
    data class Pairing(val host: HostRef) : ConnectionUi
    data class Connecting(val host: HostRef) : ConnectionUi
    data class Connected(val host: HostRef) : ConnectionUi
    data class Disconnecting(val host: HostRef?) : ConnectionUi
    data class Failed(
        val reason: FailReason,
        val host: HostRef?,
        val cause: ConnectionFailure? = null,
        val whilePairing: Boolean = false,
    ) : ConnectionUi
}

/** The one function that reads the manager's state for the UI. Total: every input yields one [ConnectionUi]. */
fun connectionUi(input: ConnectionInputs): ConnectionUi {
    val bt = input.bluetoothState
    when (bt) {
        BluetoothState.Unsupported -> return ConnectionUi.Blocked(BlockReason.NO_ADAPTER)
        BluetoothState.PermissionRequired -> return ConnectionUi.Blocked(BlockReason.PERMISSION_MISSING)
        BluetoothState.BluetoothOff -> return ConnectionUi.Blocked(BlockReason.BLUETOOTH_OFF)
        BluetoothState.ProfileNotSupported -> return ConnectionUi.Blocked(BlockReason.NO_HID_ROLE)
        else -> Unit
    }
    val connected = input.connectedAddress?.let(input::host)
        ?: (bt as? BluetoothState.Connected)?.let { HostRef(address = "", name = it.deviceName, bonded = true) }
    if (connected != null) {
        return if (input.isDisconnecting) ConnectionUi.Disconnecting(connected) else ConnectionUi.Connected(connected)
    }
    if (input.isDisconnecting) return ConnectionUi.Disconnecting(null)

    val lifecycle = input.lifecycle
    if (lifecycle is HidLifecycleState.BindingProxy) return ConnectionUi.Starting(lifecycle.attempt)
    if (lifecycle is HidLifecycleState.Registering) return ConnectionUi.Starting(lifecycle.attempt)
    if (bt is BluetoothState.InitializingCapabilities || bt is BluetoothState.CheckingCapabilities) {
        return ConnectionUi.Checking
    }
    if (lifecycle is HidLifecycleState.Error && lifecycle.failure != HidFailure.CONNECTION_REJECTED) {
        return ConnectionUi.Failed(FailReason.SERVICE_FAILED, host = null)
    }

    val target = input.targetAddress?.let(input::host)
        ?: (lifecycle as? HidLifecycleState.Connecting)?.deviceAddress?.let(input::host)
    if (target != null) {
        val pairing = !target.bonded
        val started = input.attemptStartedAt
        if (started != null && input.now - started >= CONNECTION_STALL_MILLIS) {
            return ConnectionUi.Failed(FailReason.STALLED, target, whilePairing = pairing)
        }
        return if (pairing) ConnectionUi.Pairing(target) else ConnectionUi.Connecting(target)
    }

    // A new scan is a new start: it reads as scanning, not as the failure before it.
    if (input.isScanning) {
        return ConnectionUi.Ready(input.reconnectAddress?.let(input::host)?.takeIf { it.bonded }, scanning = true)
    }
    input.lastFailure?.let {
        return ConnectionUi.Failed(FailReason.HOST_REFUSED, input.host(it.hostAddress), cause = it.failure)
    }
    if (lifecycle == HidLifecycleState.Error(HidFailure.CONNECTION_REJECTED)) {
        return ConnectionUi.Failed(FailReason.HOST_REFUSED, host = null, cause = ConnectionFailure.CONNECTION_REJECTED)
    }
    return ConnectionUi.Ready(
        reconnectHost = input.reconnectAddress?.let(input::host)?.takeIf { it.bonded },
        scanning = input.isScanning,
    )
}

/**
 * What identifies one attempt for the stall clock: the host and its phase. Pairing that turns into connecting is a
 * new phase, so the clock restarts instead of reading a healthy connection as stalled.
 */
fun attemptKey(connectedAddress: String?, targetAddress: String?, lifecycle: HidLifecycleState, bondedAddresses: Set<String>): String? {
    if (connectedAddress != null) return null
    val address = targetAddress ?: (lifecycle as? HidLifecycleState.Connecting)?.deviceAddress ?: return null
    return "$address/${if (address in bondedAddresses) "connecting" else "pairing"}"
}

/** Input modes can launch unless something blocks Bluetooth outright. */
val ConnectionUi.allowsLaunch: Boolean get() = this !is ConnectionUi.Blocked

/** An open input surface goes back home when this is true; Checking and Starting leave it open. */
val ConnectionUi.closesSurface: Boolean get() = this is ConnectionUi.Blocked

enum class StatusTone { NEUTRAL, WORKING, GOOD, PROBLEM }

/** A step the card can offer. */
sealed interface ConnectionAction {
    data object GrantPermission : ConnectionAction
    data object TurnOnBluetooth : ConnectionAction
    data object RetryCheck : ConnectionAction
    data class Reconnect(val host: HostRef) : ConnectionAction
    data class Retry(val host: HostRef) : ConnectionAction
    data object Scan : ConnectionAction
    data object StopScan : ConnectionAction
    data object Cancel : ConnectionAction
    data object Disconnect : ConnectionAction
    data object RestartHid : ConnectionAction
    data object Help : ConnectionAction
}

val ConnectionAction.label: String
    get() = when (this) {
        ConnectionAction.GrantPermission -> "Grant permission"
        ConnectionAction.TurnOnBluetooth -> "Turn on Bluetooth"
        ConnectionAction.RetryCheck -> "Retry"
        is ConnectionAction.Reconnect -> "Reconnect ${host.displayName}"
        is ConnectionAction.Retry -> "Try again"
        ConnectionAction.Scan -> "Scan for hosts"
        ConnectionAction.StopScan -> "Stop scanning"
        ConnectionAction.Cancel -> "Cancel"
        ConnectionAction.Disconnect -> "Disconnect"
        ConnectionAction.RestartHid -> "Restart Bluetooth input"
        ConnectionAction.Help -> "Help"
    }

/** What the connection card shows for one [ConnectionUi]: one headline, one dot, at most one primary action. */
data class ConnectionCard(
    val headline: String,
    val supporting: String?,
    val tone: StatusTone,
    val busy: Boolean,
    val primary: ConnectionAction?,
    /** A quieter second step; only ever Help, beside a state that carries help. */
    val secondary: ConnectionAction? = null,
)

fun ConnectionUi.card(): ConnectionCard = when (this) {
    ConnectionUi.Checking -> ConnectionCard(
        "Checking Bluetooth",
        "Making sure this phone can act as a keyboard, mouse and controller.",
        StatusTone.NEUTRAL, busy = true, primary = null,
    )
    is ConnectionUi.Starting -> ConnectionCard(
        "Starting Bluetooth",
        if (attempt > 1) "Getting Bluetooth input ready, attempt $attempt." else "Getting Bluetooth input ready.",
        StatusTone.WORKING, busy = true, primary = null,
    )
    is ConnectionUi.Blocked -> when (reason) {
        BlockReason.PERMISSION_MISSING -> ConnectionCard(
            "Bluetooth permission needed",
            "Bluke pairs with your computer, TV or tablet and acts as its keyboard, mouse and controller. " +
                "Android asks first: allow Nearby devices.",
            StatusTone.NEUTRAL, busy = false, primary = ConnectionAction.GrantPermission,
        )
        BlockReason.BLUETOOTH_OFF -> ConnectionCard(
            "Bluetooth is off",
            "Turn it on to connect to a host.",
            StatusTone.NEUTRAL, busy = false, primary = ConnectionAction.TurnOnBluetooth,
        )
        BlockReason.NO_ADAPTER -> ConnectionCard(
            "No Bluetooth on this phone",
            "Bluke needs Bluetooth hardware to act as a keyboard.",
            StatusTone.PROBLEM, busy = false, primary = null,
        )
        BlockReason.NO_HID_ROLE -> ConnectionCard(
            "This phone can't act as a keyboard",
            "Its Bluetooth firmware doesn't let apps act as a keyboard or mouse. " +
                "Turn Bluetooth off and on, then retry before giving up.",
            StatusTone.PROBLEM, busy = false, primary = ConnectionAction.RetryCheck, secondary = ConnectionAction.Help,
        )
    }
    is ConnectionUi.Ready -> when {
        scanning -> ConnectionCard(
            "Looking for hosts",
            "Open Bluetooth settings on your computer, TV or tablet so it shows up below.",
            StatusTone.WORKING, busy = true, primary = ConnectionAction.StopScan,
        )
        reconnectHost != null -> ConnectionCard(
            "Not connected",
            "Last used with ${reconnectHost.displayName}.",
            StatusTone.NEUTRAL, busy = false, primary = ConnectionAction.Reconnect(reconnectHost),
        )
        else -> ConnectionCard(
            "Not connected",
            "Scan to find your computer, TV or tablet.",
            StatusTone.NEUTRAL, busy = false, primary = ConnectionAction.Scan,
        )
    }
    is ConnectionUi.Pairing -> ConnectionCard(
        "Pairing with ${host.displayName}",
        "Accept the pairing prompt on this phone and on ${host.displayName}.",
        // Pairing waits on a person, not on progress, so it holds still instead of spinning.
        StatusTone.WORKING, busy = false, primary = ConnectionAction.Cancel,
    )
    is ConnectionUi.Connecting -> ConnectionCard(
        "Connecting to ${host.displayName}",
        "This usually takes a few seconds.",
        StatusTone.WORKING, busy = true, primary = ConnectionAction.Cancel,
    )
    is ConnectionUi.Connected -> ConnectionCard(
        "Connected to ${host.displayName}",
        "Pick a mode below to start.",
        StatusTone.GOOD, busy = false, primary = ConnectionAction.Disconnect,
    )
    is ConnectionUi.Disconnecting -> ConnectionCard(
        if (host != null) "Disconnecting from ${host.displayName}" else "Disconnecting",
        null,
        StatusTone.WORKING, busy = true, primary = null,
    )
    is ConnectionUi.Failed -> failedCard()
}

private fun ConnectionUi.Failed.failedCard(): ConnectionCard {
    val name = host?.displayName ?: "the host"
    return when (reason) {
        FailReason.SERVICE_FAILED -> ConnectionCard(
            "Bluetooth input stopped",
            "Restart it to connect again. If that doesn't work, turn Bluetooth off and on.",
            StatusTone.PROBLEM, busy = false, primary = ConnectionAction.RestartHid, secondary = ConnectionAction.Help,
        )
        FailReason.STALLED -> ConnectionCard(
            if (whilePairing) "Still pairing with $name" else "Still connecting to $name",
            if (whilePairing) {
                "Look for a pairing prompt on both devices and accept it."
            } else {
                "Check that it's on and nearby. If it's stuck, cancel and try again."
            },
            StatusTone.WORKING, busy = !whilePairing, primary = ConnectionAction.Cancel, secondary = ConnectionAction.Help,
        )
        FailReason.HOST_REFUSED -> {
            val retry = host?.let { ConnectionAction.Retry(it) } ?: ConnectionAction.Scan
            when (cause) {
                ConnectionFailure.HOST_REFUSED_PAIRING -> ConnectionCard(
                    "Host refused pairing",
                    "Accept the prompt on both devices. If it keeps failing, remove Bluke on $name, forget $name on this phone, then pair again.",
                    StatusTone.PROBLEM, busy = false, primary = retry, secondary = ConnectionAction.Help,
                )
                ConnectionFailure.PAIRING_REQUEST_FAILED -> ConnectionCard(
                    "Couldn't start pairing",
                    "Android didn't start pairing with $name. Try again, or turn Bluetooth off and on.",
                    StatusTone.PROBLEM, busy = false, primary = retry, secondary = ConnectionAction.Help,
                )
                ConnectionFailure.CONNECTION_REJECTED, null -> ConnectionCard(
                    "Couldn't connect to $name",
                    "If you paired from the host's Bluetooth settings, forget the pairing on both devices, then pair again from Bluke.",
                    StatusTone.PROBLEM, busy = false, primary = retry, secondary = ConnectionAction.Help,
                )
            }
        }
    }
}

/** Restart HID sits in the card's details wherever it can help and isn't already the primary action. */
val ConnectionUi.offersRestartInDetails: Boolean
    get() = when (this) {
        is ConnectionUi.Ready, is ConnectionUi.Connected, is ConnectionUi.Connecting, is ConnectionUi.Pairing -> true
        is ConnectionUi.Failed -> reason != FailReason.SERVICE_FAILED
        else -> false
    }

/** The host this state is about, for the details' MAC line. */
val ConnectionUi.host: HostRef?
    get() = when (this) {
        is ConnectionUi.Connected -> host
        is ConnectionUi.Connecting -> host
        is ConnectionUi.Pairing -> host
        is ConnectionUi.Disconnecting -> host
        is ConnectionUi.Failed -> host
        is ConnectionUi.Ready -> reconnectHost
        else -> null
    }

/** A tap on a Blocked chip that clears its cause: the chip's action word and what TalkBack says it does. */
enum class BlockedFix(val label: String, val clickLabel: String, val icon: ImageVector) {
    TurnOnBluetooth("Turn on", "Turn on Bluetooth", Icons.Default.Bluetooth),
    // Home owns the runtime request; from a surface, Bluke's settings page is where it's granted.
    OpenAppSettings("Allow", "Allow nearby devices", Icons.Default.Settings),
}

/** The block's cause in a few words, short enough to stay whole in a portrait chip at large text. */
val BlockReason.chipReason: String
    get() = when (this) {
        BlockReason.BLUETOOTH_OFF -> "Bluetooth off"
        BlockReason.PERMISSION_MISSING -> "Nearby devices permission needed"
        BlockReason.NO_ADAPTER -> "No Bluetooth on this phone"
        BlockReason.NO_HID_ROLE -> "Phone not supported"
    }

/** The fix a tap can make; no tap gives a phone Bluetooth or an HID role. */
val BlockReason.fix: BlockedFix?
    get() = when (this) {
        BlockReason.BLUETOOTH_OFF -> BlockedFix.TurnOnBluetooth
        BlockReason.PERMISSION_MISSING -> BlockedFix.OpenAppSettings
        BlockReason.NO_ADAPTER, BlockReason.NO_HID_ROLE -> null
    }

/**
 * The input surfaces' host chip, read from the same state as the card, so "connected" has one source.
 * Checking and Starting read as Connecting (the surface stays open and says so); the chip reconnects to a
 * bonded host [onReconnect] names, and a Blocked chip runs [onFix] when a tap can clear the cause.
 */
fun ConnectionUi.surfaceHost(
    onReconnect: (HostRef) -> Unit = {},
    onFix: (BlockedFix) -> Unit = {},
): SurfaceHost {
    fun offline(host: HostRef?, reconnect: HostRef?) = SurfaceHost(
        SurfaceHostStatus.Offline,
        host?.displayName,
        onReconnect = reconnect?.let { { onReconnect(it) } },
    )
    return when (this) {
        is ConnectionUi.Connected -> SurfaceHost(SurfaceHostStatus.Connected, host.displayName)
        is ConnectionUi.Connecting -> SurfaceHost(SurfaceHostStatus.Connecting, host.displayName)
        is ConnectionUi.Pairing -> SurfaceHost(SurfaceHostStatus.Connecting, host.displayName)
        ConnectionUi.Checking, is ConnectionUi.Starting -> SurfaceHost(SurfaceHostStatus.Connecting, null)
        // Input has stopped going out; the chip says "Connecting to" otherwise, which this isn't.
        is ConnectionUi.Disconnecting -> offline(host, reconnect = null)
        is ConnectionUi.Ready -> offline(reconnectHost, reconnectHost)
        is ConnectionUi.Failed -> offline(host, if (reason == FailReason.STALLED) null else host?.takeIf { it.bonded })
        is ConnectionUi.Blocked -> {
            val fix = reason.fix
            SurfaceHost(
                SurfaceHostStatus.Blocked,
                null,
                reason = reason.chipReason,
                onFix = fix?.let { { onFix(it) } },
                fixLabel = fix?.label,
                fixClickLabel = fix?.clickLabel,
                fixIcon = fix?.icon,
            )
        }
    }
}

/** How one host's row reads, from the same state the card reads, never from status text. */
enum class HostRowState {
    IDLE, LAST_USED, WORKING, DISCONNECTING, CONNECTED, FAILED,

    /** The connection is busy with something a host tap can't change, so rows read as plain devices and take no taps. */
    INERT,
}

/** A tap on a row in this state starts a connection the card and row will show. */
val HostRowState.acceptsTap: Boolean
    get() = this == HostRowState.IDLE || this == HostRowState.FAILED || this == HostRowState.LAST_USED

/** States that take precedence over any queued target, so a row tap would change nothing on screen. */
private val ConnectionUi.ignoresHostTaps: Boolean
    get() = when (this) {
        ConnectionUi.Checking, is ConnectionUi.Starting, is ConnectionUi.Disconnecting, is ConnectionUi.Blocked -> true
        is ConnectionUi.Failed -> reason == FailReason.SERVICE_FAILED
        else -> false
    }

fun ConnectionUi.rowState(address: String): HostRowState = when {
    this is ConnectionUi.Connected && host.address == address -> HostRowState.CONNECTED
    this is ConnectionUi.Disconnecting && host?.address == address -> HostRowState.DISCONNECTING
    ignoresHostTaps -> HostRowState.INERT
    this is ConnectionUi.Connecting && host.address == address -> HostRowState.WORKING
    this is ConnectionUi.Pairing && host.address == address -> HostRowState.WORKING
    this is ConnectionUi.Failed && host?.address == address ->
        if (reason == FailReason.STALLED) HostRowState.WORKING else HostRowState.FAILED
    // The card already offers Reconnect for this host, so its row doesn't offer it again.
    this is ConnectionUi.Ready && reconnectHost?.address == address -> HostRowState.LAST_USED
    else -> HostRowState.IDLE
}
