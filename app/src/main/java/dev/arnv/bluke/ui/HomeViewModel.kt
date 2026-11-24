package dev.arnv.bluke.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.HidLifecycleState
import dev.arnv.bluke.bluetooth.LastConnectionFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Everything the home screen renders, already decided: no field here needs interpreting. */
data class HomeUiState(
    val connection: ConnectionUi = ConnectionUi.Checking,
    /** The manager's own words for its last step, shown verbatim in Details and never parsed. */
    val statusLine: String = "",
    val paired: List<HostRef> = emptyList(),
    val nearby: List<HostRef> = emptyList(),
    val isScanning: Boolean = false,
    val connectedDevice: BluetoothDevice? = null,
    val capsLock: Boolean = false,
    val numLock: Boolean = true,
    val scrollLock: Boolean = false,
)

/** The manager's flows at one instant. */
private data class ManagerSnapshot(
    val bluetoothState: BluetoothState,
    val lifecycle: HidLifecycleState,
    val statusLine: String,
    val bonded: List<BluetoothDevice>,
    val scanned: List<BluetoothDevice>,
    val isScanning: Boolean,
    val connected: BluetoothDevice?,
    val targetAddress: String?,
    val isDisconnecting: Boolean,
    val lastFailure: LastConnectionFailure?,
    val capsLock: Boolean,
    val numLock: Boolean,
    val scrollLock: Boolean,
)

/** The filters the Nearby list reads from Settings. */
data class HostListFilters(val hideUnnamed: Boolean = false, val hideUnlikely: Boolean = true)

/** The Nearby filters, re-read whenever Settings changes them. */
fun SharedPreferences.hostListFilters(): Flow<HostListFilters> = callbackFlow {
    fun read() = HostListFilters(
        hideUnnamed = getBoolean("hide_unknown", false),
        hideUnlikely = getBoolean("hide_unsupported", true),
    )
    trySend(read())
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hide_unknown" || key == "hide_unsupported") trySend(read())
    }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

private val HID_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("00001124-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
internal fun BluetoothDevice.toHostRef(): HostRef {
    val name = runCatching { name }.getOrNull()
    val bluetoothClass = runCatching { bluetoothClass }.getOrNull()
    return HostRef(
        address = address,
        name = name,
        bonded = runCatching { bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
        hidCapable = runCatching { uuids?.contains(HID_SERVICE_UUID) == true }.getOrDefault(false),
        type = classifyHost(name, bluetoothClass?.majorDeviceClass, bluetoothClass?.deviceClass),
    )
}

/** Tracks when the attempt in flight began, so a long one reads as stalled. */
private class AttemptClock(private val now: () -> Long) {
    private var key: String? = null
    private var startedAt: Long? = null

    @Synchronized
    fun startedAt(attemptKey: String?): Long? {
        if (attemptKey != key) {
            key = attemptKey
            startedAt = attemptKey?.let { now() }
        }
        return startedAt
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val manager: BluetoothKeyboardManager,
    filters: Flow<HostListFilters>,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val attemptClock = AttemptClock(now)

    private val snapshot: Flow<ManagerSnapshot> = combine(
        combine(manager.serviceState, manager.lifecycleState, manager.statusMessage, ::Triple),
        combine(manager.bondedDevices, manager.scannedDevices, manager.isScanning, ::Triple),
        combine(manager.connectedDevice, manager.connectionTargetAddress, manager.isDisconnecting, manager.lastConnectionFailure) {
                connected, target, disconnecting, failure -> listOf(connected, target, disconnecting, failure)
        },
        combine(manager.capsLockState, manager.numLockState, manager.scrollLockState, ::Triple),
    ) { (bt, lifecycle, status), (bonded, scanned, scanning), link, (caps, num, scroll) ->
        ManagerSnapshot(
            bluetoothState = bt,
            lifecycle = lifecycle,
            statusLine = status,
            bonded = bonded,
            scanned = scanned,
            isScanning = scanning,
            connected = link[0] as BluetoothDevice?,
            targetAddress = link[1] as String?,
            isDisconnecting = link[2] as Boolean,
            lastFailure = link[3] as LastConnectionFailure?,
            capsLock = caps,
            numLock = num,
            scrollLock = scroll,
        )
    }

    /** Ticks once a second while an attempt is in flight, so Stalled appears on time; silent otherwise. */
    private val clock: Flow<Long> = snapshot
        .map { attemptKey(it) != null }
        .distinctUntilChanged()
        .flatMapLatest { attempting ->
            if (!attempting) flowOf(0L) else flow {
                while (true) {
                    emit(now())
                    delay(1_000)
                }
            }
        }

    val uiState: StateFlow<HomeUiState> = combine(snapshot, filters, clock) { s, f, _ -> build(s, f) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            build(currentSnapshot(), HostListFilters()),
        )

    private fun currentSnapshot() = ManagerSnapshot(
        bluetoothState = manager.serviceState.value,
        lifecycle = manager.lifecycleState.value,
        statusLine = manager.statusMessage.value,
        bonded = manager.bondedDevices.value,
        scanned = manager.scannedDevices.value,
        isScanning = manager.isScanning.value,
        connected = manager.connectedDevice.value,
        targetAddress = manager.connectionTargetAddress.value,
        isDisconnecting = manager.isDisconnecting.value,
        lastFailure = manager.lastConnectionFailure.value,
        capsLock = manager.capsLockState.value,
        numLock = manager.numLockState.value,
        scrollLock = manager.scrollLockState.value,
    )

    private fun attemptKey(s: ManagerSnapshot): String? =
        attemptKey(s.connected?.address, s.targetAddress, s.lifecycle, s.bonded.map { it.address }.toSet())

    private fun build(s: ManagerSnapshot, filters: HostListFilters): HomeUiState {
        val bonded = s.bonded.map { it.toHostRef() }
        val scanned = s.scanned.map { it.toHostRef() }
        val known = (scanned + bonded + listOfNotNull(s.connected?.toHostRef())).associateBy { it.address }
        val inputs = ConnectionInputs(
            bluetoothState = s.bluetoothState,
            lifecycle = s.lifecycle,
            connectedAddress = s.connected?.address,
            targetAddress = s.targetAddress,
            isScanning = s.isScanning,
            isDisconnecting = s.isDisconnecting,
            lastFailure = s.lastFailure,
            reconnectAddress = manager.getReconnectTarget()?.address,
            knownHosts = known,
            attemptStartedAt = attemptClock.startedAt(attemptKey(s)),
            now = now(),
        )
        val (paired, nearby) = hostLists(bonded, scanned, filters.hideUnnamed, filters.hideUnlikely)
        return HomeUiState(
            connection = connectionUi(inputs),
            statusLine = s.statusLine,
            paired = paired,
            nearby = nearby,
            isScanning = s.isScanning,
            connectedDevice = s.connected,
            capsLock = s.capsLock,
            numLock = s.numLock,
            scrollLock = s.scrollLock,
        )
    }

    /** The paired or found device at [address], for actions that need the framework object. */
    fun device(address: String): BluetoothDevice? =
        manager.bondedDevices.value.firstOrNull { it.address == address }
            ?: manager.scannedDevices.value.firstOrNull { it.address == address }
            ?: manager.connectedDevice.value?.takeIf { it.address == address }

    companion object {
        fun factory(manager: BluetoothKeyboardManager, filters: Flow<HostListFilters>): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java))
                    return HomeViewModel(manager, filters) as T
                }
            }
    }
}
