package dev.arnv.bluke.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.arnv.bluke.R
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.arnv.bluke.utils.DeveloperLogManager
import dev.arnv.bluke.utils.LogType
import androidx.core.content.edit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

sealed class BluetoothState {
    object InitializingCapabilities : BluetoothState()
    object CheckingCapabilities : BluetoothState()
    object Unsupported : BluetoothState()
    object PermissionRequired : BluetoothState()
    object BluetoothOff : BluetoothState()
    object ProfileNotSupported : BluetoothState()
    object ReadyDisconnected : BluetoothState()
    data class PairingMode(val name: String) : BluetoothState()
    data class Connected(val deviceName: String) : BluetoothState()
}

class BluetoothKeyboardManager(private val context: Context) {
    private companion object {
        const val PROXY_CALLBACK_TIMEOUT_MILLIS = 8_000L
        const val STALE_REGISTRATION_SETTLE_MILLIS = 300L
        const val PREF_DISCONNECT_AUDIO_PROFILES = "disconnect_audio_profiles"
    }

    private val reportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            runnable.run()
        }, "bt-report-sender")
    }

    private val reportDiagnostics = HidReportDiagnostics()

    // The latest input report submitted under each ID, and the host's last LED byte: a host's GET_REPORT is answered from these.
    private val lastInputReports = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
    @Volatile
    private var ledOutputByte: Byte = 0

    private fun logHidEvent(event: String) {
        if (!DeveloperLogManager.isEnabled) return
        val message = "t=${android.os.SystemClock.elapsedRealtime()} thread=${Thread.currentThread().name} " +
            "$event epoch=${connectionEpoch.get()} foreground=$appInForeground " +
            "registered=${appRegistrationState.value} pending=${_hasPendingConnection.value} " +
            "target=${_connectionTargetAddress.value} host=${_connectedDevice.value?.address} closed=$closed"
        Log.d("BlukeLifecycle", message)
        DeveloperLogManager.log("BlukeLifecycle", message)
    }

    // When input last went out and when anything did (keepalives included): see LinkKeepAlive.
    @Volatile private var lastInputAt = Long.MIN_VALUE / 2
    @Volatile private var lastSentAt = Long.MIN_VALUE / 2
    private var keepAliveTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val keepAliveLock = Any()

    @SuppressLint("MissingPermission")
    private fun submitReport(dev: BluetoothDevice, reportId: Int, report: ByteArray, isKeepAlive: Boolean = false) {
        val hid = hidDeviceProfile
        val epoch = connectionEpoch.get()
        val reportMode = gamepadDpadOutputMode
        if (hid != null && !closed) {
            lastInputReports[reportId] = report.copyOf()
            val now = android.os.SystemClock.uptimeMillis()
            lastSentAt = now
            if (!isKeepAlive) {
                lastInputAt = now
                startKeepAlive()
            }
            try {
            reportExecutor.submit {
                if (closed || epoch != connectionEpoch.get() || hid !== hidDeviceProfile ||
                    _connectedDevice.value?.address != dev.address) return@submit
                // Android holds a report sent to a dropped host and reconnects to deliver it, so a keepalive
                // must never outlive the link: not while it is changing, nor once the host has left but
                // our callback hasn't arrived yet.
                if (isKeepAlive && (_hasPendingConnection.value ||
                        hid.getConnectionState(dev) != BluetoothProfile.STATE_CONNECTED)) return@submit
                try {
                    val accepted = hid.sendReport(dev, reportId, report)
                    if (DeveloperLogManager.isEnabled) {
                        reportDiagnostics.record(reportId, accepted, android.os.SystemClock.elapsedRealtime())?.let { summary ->
                            val axes = if (reportId == GAMEPAD_REPORT_ID) gamepadAxisDiagnostic(report) else ""
                            val message = "sendReport ID=0x${reportId.toString(16)} $summary " +
                                "mode=$reportMode epoch=$epoch $axes " +
                                "Data=[${report.joinToString(" ") { String.format("%02X", it) }}]"
                            Log.d("BlukeHID", message)
                            DeveloperLogManager.log("BlukeHID", message, LogType.BLUETOOTH_PACKET)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BluetoothKeyboard", "Error transmitting HID report ID $reportId", e)
                    DeveloperLogManager.log("BlukeHID", "sendReport ID=$reportId exception=${e.javaClass.simpleName}", LogType.ERROR)
                }
            }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // close() may shut down the executor between the guard and submission.
            }
        }
    }

    private val _serviceState = MutableStateFlow<BluetoothState>(BluetoothState.InitializingCapabilities)
    val serviceState: StateFlow<BluetoothState> = _serviceState

    private val _statusMessage = MutableStateFlow("Initializing Bluetooth Controller...")
    val statusMessage: StateFlow<String> = _statusMessage



    // Device lists for scan / connect UI
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _capsLockState = MutableStateFlow(false)
    val capsLockState: StateFlow<Boolean> = _capsLockState

    private val _numLockState = MutableStateFlow(true)
    val numLockState: StateFlow<Boolean> = _numLockState
    // Set while a Num Lock press we sent waits for the host's LED report, so taps don't toggle it twice.
    private val numLockTogglePending = AtomicBoolean(false)

    private val _scrollLockState = MutableStateFlow(false)
    val scrollLockState: StateFlow<Boolean> = _scrollLockState

    private val bluetoothAdapter: BluetoothAdapter? = try {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    } catch (_: Exception) {
        null
    }

    @Volatile private var hidDeviceProfile: BluetoothHidDevice? = null
    @Volatile private var closed = false
    private val connectionEpoch = AtomicLong()
    private val connectionSelectionLock = Any()
    // removed bare isAppRegistered primitive in favor of thread-safe appRegistrationState
    private var lastConnectedDevice: BluetoothDevice? = null
    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "bt-manager-scheduler")
    }

    private val managerJob = SupervisorJob()
    private val callbackDispatcher = executor.asCoroutineDispatcher()
    @Volatile private var activeRegistrationAttempt: HidRegistrationAttempt? = null
    private val managerScope = CoroutineScope(managerJob + Dispatchers.IO)
    private val appRegistrationState = MutableStateFlow(false)
    private val isAppRegistered: Boolean get() = appRegistrationState.value
    private val _lifecycleState = MutableStateFlow<HidLifecycleState>(HidLifecycleState.Idle)
    val lifecycleState: StateFlow<HidLifecycleState> = _lifecycleState
    private val bindingMutex = Mutex()
    private val registrationMutex = Mutex()
    private val serviceStateLock = Any()
    private val capabilityCheckLock = Any()
    private val capabilityCheckGeneration = AtomicLong()
    @Volatile private var capabilityCheckJob: Job? = null
    @Volatile private var pendingProxyBinding: CompletableDeferred<BluetoothHidDevice?>? = null
    @Volatile private var incompatibleVerdictLatched = false
    @Volatile private var incompatibleVerdictMessage: String? = null
    @Volatile private var registrationCommandAccepted = false
    // Counts the times the adapter has been seen not on, so a failure can tell whether an off/on transition overlapped it.
    private val adapterCycle = AtomicLong()
    // Set when the latest binding or registration failure was the transition's, not the phone's: it reruns on STATE_ON.
    @Volatile private var rerunOnStateOn = false
    private val retryPolicy = RetryPolicy()
    private var isReceiverRegistered = false
    private var isBondReceiverRegistered = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                logHidEvent("broadcast adapter state=${intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)} previous=${intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)}")
                checkBluetoothCapabilities()
            } else if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                logHidEvent("broadcast bond device=${device?.address} state=$bondState previous=$prevBondState")
                
                if (device != null) {
                    val dName = device.name ?: device.address
                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            if (_connectionTargetAddress.value == device.address) {
                                _statusMessage.value = "Pairing with '$dName'... Please accept the pairing prompt."
                            }
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            updateBondedDevices()
                            if (_connectionTargetAddress.value == device.address) {
                                connectDevice(device)
                            }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            updateBondedDevices()
                            synchronized(connectionSelectionLock) {
                            if (_connectionTargetAddress.value != device.address) return
                            connectRequestProcessor.clear()
                            _hasPendingConnection.value = false
                            _connectionTargetAddress.value = null
                            if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                _lastConnectionFailure.value =
                                    LastConnectionFailure(ConnectionFailure.HOST_REFUSED_PAIRING, device.address)
                                _statusMessage.value = "Pairing with '$dName' refused or failed."
                            } else {
                                _statusMessage.value = "Unpaired from '$dName'."
                            }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (!isBondReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(bondStateReceiver, filter)
                }
                isBondReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error registering bond receiver: ${e.message}", e)
            }
        }
    }

    // 8-byte Keyboard HID report parameters
    private var activeModifiers = 0
    private val activeKeys = ByteArray(6)

    // Discovery receiver to catch found devices and scanning events
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val currentList = _scannedDevices.value
                        if (!currentList.any { it.address == device.address }) {
                            _scannedDevices.value = currentList + device
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    // Composite Keyboard, Mouse/Trackpad, Gamepad & Consumer Control HID Descriptor definition

    private fun sdpSettings(mode: GamepadDpadOutputMode): BluetoothHidDeviceAppSdpSettings? {
        return try {
            BluetoothHidDeviceAppSdpSettings(
                "Bluke",                         // Name
                "Wireless Controller Combo",    // Description
                "Bluke",                         // Provider
                BluetoothHidDevice.SUBCLASS1_COMBO, // Subclass
                hidDescriptorForMode(mode)       // One descriptor for every report mapping
            )
        } catch (e: Throwable) {
            Log.e("BlukeBT", "Failed to create BluetoothHidDeviceAppSdpSettings", e)
            null
        }
    }

    private val sharedPrefs = context.getSharedPreferences("bluetooth_keyboard_prefs", Context.MODE_PRIVATE)
    private val appPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    @Volatile
    private var gamepadDpadOutputMode = GamepadDpadOutputMode.fromPreference(
        appPreferences.getString(GAMEPAD_DPAD_MODE_PREFERENCE, null),
    )
    internal fun changeGamepadMode(mode: GamepadDpadOutputMode): Boolean = synchronized(connectionSelectionLock) {
        if (closed) return false
        appPreferences.edit { putString(GAMEPAD_DPAD_MODE_PREFERENCE, mode.preferenceValue) }
        true
    }
    private val behaviorPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == GAMEPAD_DPAD_MODE_PREFERENCE) {
                val newMode = GamepadDpadOutputMode.fromPreference(
                    preferences.getString(GAMEPAD_DPAD_MODE_PREFERENCE, null),
                )
                if (newMode != gamepadDpadOutputMode) {
                    _connectedDevice.value?.let { device ->
                        submitReport(
                            device,
                            GAMEPAD_REPORT_ID,
                            buildGamepadReport(0, 0, 0f, 0f, 0f, 0f, gamepadDpadOutputMode),
                        )
                    }
                    gamepadDpadOutputMode = newMode
                    _connectedDevice.value?.let { device ->
                        submitReport(
                            device,
                            GAMEPAD_REPORT_ID,
                            buildGamepadReport(0, 0, 0f, 0f, 0f, 0f, newMode),
                        )
                    }
                }
            }
        }

    private var lastConnectedDeviceAddress: String?
        get() = sharedPrefs.getString("last_connected_device_address", null)
        set(value) {
            if (value == null) {
                sharedPrefs.edit { remove("last_connected_device_address") }
            } else {
                sharedPrefs.edit {
                    putString("last_connected_device_address", value)
                    putString("last_successful_host_address", value)
                }
            }
        }

    private data class ConnectRequest(
        val sequence: Long,
        val device: BluetoothDevice,
        val delayMillis: Long,
        val automatic: Boolean = false,
    )

    private val connectSequence = AtomicLong()
    private val connectRequestProcessor = LatestRequestProcessor(managerScope, ::connectWhenReady)
    private val _connectionTargetAddress = MutableStateFlow<String?>(null)
    val connectionTargetAddress: StateFlow<String?> = _connectionTargetAddress
    private val _hasPendingConnection = MutableStateFlow(false)
    val hasPendingConnection: StateFlow<Boolean> = _hasPendingConnection
    private val _lastConnectionFailure = MutableStateFlow<LastConnectionFailure?>(null)
    /** Why the last attempt failed, set beside its status message and cleared when the next attempt starts. */
    val lastConnectionFailure: StateFlow<LastConnectionFailure?> = _lastConnectionFailure
    private val _isDisconnecting = MutableStateFlow(false)
    /** True from an explicit disconnect until its transaction finishes. */
    val isDisconnecting: StateFlow<Boolean> = _isDisconnecting
    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible
    @Volatile private var suppressIncomingConnection = false
    @Volatile private var sessionOperationJob: Job? = null
    @Volatile private var automaticReconnectAttempted = false
    private val connectionCoordinator = HidConnectionCoordinator(object : BluetoothConnectionFacade {
        @SuppressLint("MissingPermission")
        override fun state(address: String): HostLinkState = when (
            hidDeviceProfile?.getConnectionState(bluetoothAdapter?.getRemoteDevice(address))
        ) {
            BluetoothProfile.STATE_CONNECTED -> HostLinkState.CONNECTED
            BluetoothProfile.STATE_CONNECTING -> HostLinkState.CONNECTING
            BluetoothProfile.STATE_DISCONNECTING -> HostLinkState.DISCONNECTING
            else -> HostLinkState.DISCONNECTED
        }
        @SuppressLint("MissingPermission")
        override fun busyAddresses(): List<String> = hidDeviceProfile?.getDevicesMatchingConnectionStates(
            intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTING),
        )?.map { it.address }.orEmpty()
        @SuppressLint("MissingPermission")
        override fun connect(address: String): Boolean {
            logHidEvent("command connect device=$address")
            return (!closed && !suppressIncomingConnection && sessionOperationJob?.isActive != true &&
                _connectionTargetAddress.value == address &&
                appRegistrationState.value && hidDeviceProfile?.connect(bluetoothAdapter?.getRemoteDevice(address)) == true)
                .also { logHidEvent("result connect device=$address accepted=$it") }
        }
        @SuppressLint("MissingPermission")
        override fun disconnect(address: String): Boolean {
            logHidEvent("command disconnect device=$address reason=connection-coordinator")
            return (!closed && hidDeviceProfile?.disconnect(bluetoothAdapter?.getRemoteDevice(address)) == true)
                .also { logHidEvent("result disconnect device=$address accepted=$it") }
        }
    })
    @Volatile
    private var lastRegistrationFailure: HidFailure? = null
    private var audioProfilesDisconnectedForSession = false
    @Volatile private var appInForeground = false
    @Volatile private var previouslyRegistered =
        appPreferences.getString("hid_supported_firmware", null) == Build.FINGERPRINT

    private fun recordSupportedFirmware() {
        previouslyRegistered = true
        appPreferences.edit { putString("hid_supported_firmware", Build.FINGERPRINT) }
    }

    fun setAppInForeground(foreground: Boolean) {
        logHidEvent("visibility requested=$foreground")
        if (closed || appInForeground == foreground) return
        appInForeground = foreground
        _appVisible.value = foreground
        if (foreground) {
            automaticReconnectAttempted = false
            checkBluetoothCapabilities()
        } else if (_connectedDevice.value == null && !_hasPendingConnection.value) {
            synchronized(capabilityCheckLock) {
                capabilityCheckGeneration.incrementAndGet()
                capabilityCheckJob?.cancel()
                capabilityCheckJob = null
            }
        }
    }

    fun isAppVisible(): Boolean = appInForeground

    @SuppressLint("MissingPermission")
    fun getReconnectTarget(): BluetoothDevice? {
        // Explicit disconnect disables automatic reconnect, but should still leave
        // the last successful, still-bonded host available for a manual reconnect.
        val address = sharedPrefs.getString("last_successful_host_address", null)
            ?: lastConnectedDeviceAddress ?: return null
        return _bondedDevices.value.firstOrNull { it.address == address }
    }

    init {
        try {
            logHidEvent("manager init manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} autoConnect=${appPreferences.getBoolean("auto_connect", true)}")
            appPreferences.registerOnSharedPreferenceChangeListener(behaviorPreferenceListener)
            checkBluetoothCapabilities()
            registerBondReceiver()
        } catch (e: Throwable) {
            Log.e("BluetoothKeyboard", "Error during init: ${e.message}", e)
            publishServiceState(BluetoothState.ProfileNotSupported)
            _statusMessage.value = "Bluetooth HID profile is not supported on this device firmware."
        }
    }

    fun checkBluetoothCapabilities() {
        if (closed) return
        if (bluetoothAdapter == null) {
            registrationCommandAccepted = false
            publishServiceState(BluetoothState.Unsupported)
            _statusMessage.value = "Bluetooth is not supported on this device's hardware."
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            publishServiceState(BluetoothState.PermissionRequired)
            return
        }
        if (adapterPower() != AdapterPower.ON) {
            // Off, or turning on or off: nothing registers until STATE_ON, whose broadcast runs this again.
            adapterCycle.incrementAndGet()
            sessionOperationJob?.cancel()
            synchronized(capabilityCheckLock) {
                capabilityCheckGeneration.incrementAndGet()
                capabilityCheckJob?.cancel()
                capabilityCheckJob = null
            }
            connectRequestProcessor.clear()
            _hasPendingConnection.value = false
            _connectionTargetAddress.value = null
            _connectedDevice.value = null
            _lastConnectionFailure.value = null
            connectionEpoch.incrementAndGet()
            registrationCommandAccepted = false
            clearIncompatibleVerdict()
            publishServiceState(BluetoothState.BluetoothOff)
            _statusMessage.value = "Bluetooth is currently turned off. Please enable Bluetooth."
            releaseHidProxy()
            activeRegistrationAttempt = null
            appRegistrationState.value = false
            lastRegistrationFailure = null
            _lifecycleState.value = HidLifecycleState.Idle
            // Coming back on reconnects the last host, as a launch does; an explicit disconnect still forgot it.
            automaticReconnectAttempted = false
            return
        }

        // Check permissions on API 31+ (BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasAdvertise = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasScan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasConnect || !hasAdvertise || !hasScan) {
                publishServiceState(BluetoothState.PermissionRequired)
                _statusMessage.value = "Bluetooth Connect, Advertise & Scan permissions are required."
                return
            }
        } else {
            // On Android 9 and 10 (API 28–30), ACCESS_FINE_LOCATION is required at runtime for
            // Bluetooth device scanning and HID profile operations. Without it, getProfileProxy
            // and startDiscovery may silently do nothing with no error in logcat.
            val hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                              context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                publishServiceState(BluetoothState.PermissionRequired)
                _statusMessage.value = "Location permission is required on Android 10 and earlier to use Bluetooth."
                Log.w("BluetoothKeyboard", "Missing ACCESS_FINE_LOCATION on API ${Build.VERSION.SDK_INT} — Bluetooth HID will not work.")
                return
            }
        }

        if (incompatibleVerdictLatched) {
            publishServiceState(BluetoothState.ProfileNotSupported)
            _statusMessage.value = incompatibleVerdictMessage
                ?: "This device appears incompatible with the Bluetooth HID Device role."
            return
        }

        if (_serviceState.value is BluetoothState.BluetoothOff) publishServiceState(BluetoothState.ReadyDisconnected)

        if (sessionOperationJob?.isActive == true) return

        if (isCapabilityCheckRunning()) {
            return
        }

        if (!appInForeground) return

        updateBondedDevices()
        // Initialize HID Device Profile safely
        val hid = hidDeviceProfile
        if (hid == null) {
            initProfileListener()
        } else if (!isAppRegistered) {
            registerApp()
        } else {
            if (_hasPendingConnection.value) return
            publishRegisteredUiState(
                scheduleReconnect = true,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun updateBondedDevices() {
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            try {
                _bondedDevices.value = bluetoothAdapter.bondedDevices.toList()
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error listing bonded devices", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (incompatibleVerdictLatched || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        _scannedDevices.value = emptyList()

        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(discoveryReceiver, filter)
                }
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error registering discovery receiver: ${e.message}", e)
                _statusMessage.value = "Failed to register scanner: ${e.localizedMessage}"
            }
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            val started = bluetoothAdapter.startDiscovery()
            if (started) {
                _isScanning.value = true
                _lastConnectionFailure.value = null // a new scan is a new start
                _statusMessage.value = "Scanning for other Bluetooth hosts..."
            } else {
                _statusMessage.value = "Failed to start Bluetooth discovery scanning."
            }
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error during discovery initialization", e)
            _statusMessage.value = "Scanning error: ${e.localizedMessage}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (bluetoothAdapter == null) return
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error stopping discovery", e)
        }
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(device: BluetoothDevice): Unit = synchronized(connectionSelectionLock) {
        logHidEvent("request pair device=${device.address} bondState=${device.bondState}")
        if (closed || incompatibleVerdictLatched) return
        if (sessionOperationJob?.isActive == true) return
        if (_hasPendingConnection.value && _connectionTargetAddress.value == device.address &&
            device.bondState == BluetoothDevice.BOND_BONDING) return
        connectRequestProcessor.clear()
        suppressIncomingConnection = false
        _lastConnectionFailure.value = null
        _connectionTargetAddress.value = device.address
        _hasPendingConnection.value = true
        stopScanning()
        val dName = device.name ?: device.address
        _statusMessage.value = "Requesting Bluetooth Pairing with '$dName'..."
        try {
            val success = device.createBond()
            logHidEvent("result createBond device=${device.address} accepted=$success")
            if (success) {
                _statusMessage.value = "Pairing requested. Approve prompt on '$dName'."
            } else {
                _hasPendingConnection.value = false
                _lastConnectionFailure.value =
                    LastConnectionFailure(ConnectionFailure.PAIRING_REQUEST_FAILED, device.address)
                _statusMessage.value = "Failed to start pairing request for '$dName'."
            }
        } catch (e: Exception) {
            _hasPendingConnection.value = false
            Log.e("BluetoothKeyboard", "Error calling createBond", e)
            _lastConnectionFailure.value =
                LastConnectionFailure(ConnectionFailure.PAIRING_REQUEST_FAILED, device.address)
            _statusMessage.value = "Pairing failed: ${e.localizedMessage}"
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice, delayMs: Long = 0, automatic: Boolean = false): Unit = synchronized(connectionSelectionLock) {
        logHidEvent("request connect device=${device.address} delayMs=$delayMs automatic=$automatic")
        if (closed || incompatibleVerdictLatched) return
        if (sessionOperationJob?.isActive == true) {
            _statusMessage.value = "Bluetooth recovery is in progress. Connect when it finishes."
            return
        }
        val pending = connectRequestProcessor.pending.value
        if (pending?.device?.address == device.address && _hasPendingConnection.value &&
            !(pending.automatic && !automatic)) return
        lastConnectedDevice = device
        _lastConnectionFailure.value = null
        stopScanning()
        val dName = device.name ?: device.address

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _statusMessage.value = "Credentials required. Swapping to pairing mode with '$dName'..."
            pairDevice(device)
            return
        }

        suppressIncomingConnection = false
        _connectionTargetAddress.value = device.address
        _hasPendingConnection.value = true

        _statusMessage.value = if (hidDeviceProfile == null) {
            "Waiting for Bluetooth HID service... Will connect shortly."
        } else {
            "Connecting to '$dName'..."
        }
        connectRequestProcessor.submit(ConnectRequest(
            sequence = connectSequence.incrementAndGet(),
            device = device,
            delayMillis = delayMs,
            automatic = automatic,
        ))
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectWhenReady(request: ConnectRequest) {
        val device = request.device
        val dName = device.name ?: device.address
        try {
            if (request.delayMillis > 0) delay(request.delayMillis)
            if (closed || incompatibleVerdictLatched) return
            // Share startup registration rather than starting a second retry cycle
            // immediately after the first one has timed out.
            val startup = capabilityCheckJob
            if (startup?.isActive == true) {
                startup.join()
                if (!appRegistrationState.value) return
            }
            val hid = ensureHidReady() ?: return
            if (closed || incompatibleVerdictLatched || hid !== hidDeviceProfile) return
            val result = connectionCoordinator.connect(device.address, allowHostSwitch = !request.automatic) { attempt ->
                _lifecycleState.value = HidLifecycleState.Connecting(device.address)
                _statusMessage.value = "Connecting to '$dName' (attempt $attempt)..."
                DeveloperLogManager.log("BluetoothKeyboard", "connect target=${device.address} request=${request.sequence} attempt=$attempt")
            }
            if (closed || _connectionTargetAddress.value != device.address || hid !== hidDeviceProfile) return
            if (result == ConnectionResult.CONNECTED) {
                publishConnectedHost(device)
            } else if (result == ConnectionResult.HOST_ALREADY_CONNECTED) {
                connectRequestProcessor.clear()
                _hasPendingConnection.value = false
                _connectionTargetAddress.value = null
                publishRegisteredUiState(scheduleReconnect = false)
            } else {
                _lifecycleState.value = HidLifecycleState.Error(
                    if (appRegistrationState.value) HidFailure.CONNECTION_REJECTED else HidFailure.REGISTRATION_TIMEOUT)
                if (!appRegistrationState.value) publishServiceState(BluetoothState.ReadyDisconnected)
                _lastConnectionFailure.value =
                    LastConnectionFailure(ConnectionFailure.CONNECTION_REJECTED, device.address)
                _statusMessage.value = when (result) {
                    ConnectionResult.COMMAND_REJECTED -> "Android could not start the connection. Retry or toggle Bluetooth."
                    ConnectionResult.DISCONNECT_TIMED_OUT -> "Previous host has not disconnected. Retry connecting."
                    else -> "Connection did not complete after repeated attempts. Retry connecting."
                }
                DeveloperLogManager.log("BluetoothKeyboard", "connect target=${device.address} result=$result")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error connecting to $dName", e)
            if (closed || connectRequestProcessor.pending.value?.sequence != request.sequence) return
            _lifecycleState.value = HidLifecycleState.Error(HidFailure.CONNECTION_REJECTED)
            _lastConnectionFailure.value =
                LastConnectionFailure(ConnectionFailure.CONNECTION_REJECTED, device.address)
            _statusMessage.value = "Failed to initiate link: ${e.localizedMessage}"
        } finally {
            // A completed/cancelled old request must never clear a newer selection.
            synchronized(connectionSelectionLock) {
            if (connectRequestProcessor.pending.value?.sequence == request.sequence) {
                _hasPendingConnection.value = false
                _connectionTargetAddress.value = null
                connectRequestProcessor.clear()
            }
            }
            rerunAfterTransition()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(): Unit = synchronized(connectionSelectionLock) {
        logHidEvent("request disconnect reason=explicit-disconnect")
        if (closed || incompatibleVerdictLatched) return
        if (sessionOperationJob?.isActive == true) return
        suppressIncomingConnection = true
        connectRequestProcessor.clear()
        _hasPendingConnection.value = false
        _connectionTargetAddress.value = null
        connectionEpoch.incrementAndGet()
        lastConnectedDeviceAddress = null
        automaticReconnectAttempted = true
        _statusMessage.value = "Disconnecting physical link..."
        _isDisconnecting.value = true
        // Keep the foreground service alive until the bounded transaction finishes.
        _hasPendingConnection.value = true
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            var failureMessage: String? = null
            try {
                val disconnected = connectionCoordinator.disconnectAll()
                if (!disconnected) failureMessage = "Device did not disconnect. Retry Disconnect or restart HID."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BluetoothKeyboard", "Disconnect failed", e)
                failureMessage = "Disconnect failed. Retry or restart HID."
            } finally {
                synchronized(connectionSelectionLock) {
                    suppressIncomingConnection = false
                    _hasPendingConnection.value = false
                    _isDisconnecting.value = false
                    if (sessionOperationJob === coroutineContext[Job]) sessionOperationJob = null
                    if (!closed && bluetoothAdapter?.isEnabled == true) {
                        publishRegisteredUiState(scheduleReconnect = false)
                        failureMessage?.let { _statusMessage.value = it }
                    }
                }
            }
        }
        sessionOperationJob = job
        job.start()
    }

    private fun initProfileListener() {
        if (incompatibleVerdictLatched || !appInForeground) return
        _statusMessage.value = "Connecting to HID service profile proxy..."
        launchCapabilityCheck()
    }

    private fun confirmIncompatibleVerdict(
        message: String,
        failure: HidFailure = HidFailure.REGISTRATION_REJECTED,
    ) {
        // A role that registered successfully is supported. Background/stack recovery
        // failures must not turn a working phone into a permanently incompatible one.
        if (!shouldDiagnoseHidIncompatibility(
                failure = failure,
                previouslyRegistered = previouslyRegistered,
                appInForeground = appInForeground,
            )) {
            publishServiceState(BluetoothState.ReadyDisconnected)
            _statusMessage.value = "Bluetooth HID connection interrupted. Retry connecting or toggle Bluetooth."
            return
        }
        synchronized(serviceStateLock) {
            registrationCommandAccepted = false
            appRegistrationState.value = false
            incompatibleVerdictMessage = message
            incompatibleVerdictLatched = true
            _serviceState.value = publishableServiceState(adapterPower(), BluetoothState.ProfileNotSupported)
            _statusMessage.value = message
        }
        connectRequestProcessor.clear()
        _hasPendingConnection.value = false
        _connectionTargetAddress.value = null
        connectionEpoch.incrementAndGet()
        _connectedDevice.value = null
        lastConnectedDevice = null
        stopScanning()
    }

    private fun clearIncompatibleVerdict() {
        synchronized(serviceStateLock) {
            incompatibleVerdictLatched = false
            incompatibleVerdictMessage = null
        }
    }

    private fun adapterPower(): AdapterPower =
        AdapterPower.of(bluetoothAdapter?.state ?: BluetoothAdapter.STATE_OFF)

    /** Publishes [requested], or what [publishableServiceState] allows instead; true only when [requested] itself went out. */
    private fun publishServiceState(requested: BluetoothState): Boolean = synchronized(serviceStateLock) {
        val state = publishableServiceState(adapterPower(), requested)
        val blocked = shouldBlockServiceStatePublication(
            incompatibleVerdictLatched = incompatibleVerdictLatched,
            publishingIncompatibleVerdict = state is BluetoothState.ProfileNotSupported,
            capabilityCheckRunning = capabilityCheckJob?.isActive == true,
            currentStateIsCapabilityCheck =
                _serviceState.value is BluetoothState.InitializingCapabilities ||
                    _serviceState.value is BluetoothState.CheckingCapabilities,
            publishingOperationalState =
                state is BluetoothState.ReadyDisconnected ||
                    state is BluetoothState.PairingMode ||
                    state is BluetoothState.Connected,
        )
        if (incompatibleVerdictLatched && blocked) {
            _serviceState.value = BluetoothState.ProfileNotSupported
            incompatibleVerdictMessage?.let { _statusMessage.value = it }
            false
        } else if (blocked) {
            false
        } else {
            _serviceState.value = state
            state == requested
        }
    }

    /**
     * Applies [hidFailureResponse] to a failure from an attempt begun in [attemptAdapterCycle]. A transition's
     * failure leaves the lifecycle idle and marks registration to rerun on STATE_ON; true when it did.
     */
    private fun deferToStateOn(attemptAdapterCycle: Long): Boolean {
        if (hidFailureResponse(attemptAdapterCycle, adapterCycle.get(), adapterPower()) != HidFailureResponse.RETRY_ON_STATE_ON) {
            return false
        }
        logHidEvent("failure overlapped an adapter off/on transition; registration reruns on STATE_ON")
        lastRegistrationFailure = null
        _lifecycleState.value = HidLifecycleState.Idle
        rerunOnStateOn = true
        return true
    }

    /** Called as a binding or registration job ends: reruns a deferred registration now if the adapter is already on. */
    private fun rerunAfterTransition() {
        if (closed || !rerunOnStateOn || adapterPower() != AdapterPower.ON) return
        executor.execute { checkBluetoothCapabilities() }
    }

    /** Closes the HID proxy, so the adapter coming back on binds exactly one fresh proxy rather than reviving a stale one beside it. */
    private fun releaseHidProxy() {
        val hid = hidDeviceProfile
        hidDeviceProfile = null
        pendingProxyBinding?.complete(null)
        pendingProxyBinding = null
        if (hid != null) {
            try {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (e: Exception) {
                Log.w("BluetoothKeyboard", "Closing the HID proxy failed", e)
            }
        }
    }

    fun retryBluetoothCapabilities() {
        registrationCommandAccepted = false
        appRegistrationState.value = false
        clearIncompatibleVerdict()
        lastRegistrationFailure = null
        _lifecycleState.value = HidLifecycleState.Idle
        publishServiceState(BluetoothState.CheckingCapabilities)
        _statusMessage.value = "Rechecking Bluetooth HID Device compatibility..."
        launchCapabilityCheck(replaceRunning = true)
    }

    private fun isCapabilityCheckRunning(): Boolean = synchronized(capabilityCheckLock) {
        capabilityCheckJob?.isActive == true
    }

    private fun launchCapabilityCheck(replaceRunning: Boolean = false) {
        logHidEvent("capability check requested replaceRunning=$replaceRunning previouslyRegistered=$previouslyRegistered")
        if (closed || incompatibleVerdictLatched || !appInForeground || sessionOperationJob?.isActive == true) return
        if (adapterPower() != AdapterPower.ON) return
        val jobToStart = synchronized(capabilityCheckLock) {
            val running = capabilityCheckJob
            if (running?.isActive == true && !replaceRunning) return
            if (replaceRunning) running?.cancel()

            val generation = capabilityCheckGeneration.incrementAndGet()
            if (!previouslyRegistered && _serviceState.value !is BluetoothState.InitializingCapabilities) {
                publishServiceState(BluetoothState.CheckingCapabilities)
            }
            val newJob = managerScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val hid = ensureHidReady()
                    val isCurrent = capabilityCheckGeneration.get() == generation
                    if (isCurrent) {
                        synchronized(capabilityCheckLock) {
                            if (capabilityCheckJob === coroutineContext[Job]) {
                                capabilityCheckJob = null
                            }
                        }
                        if (hid != null && appRegistrationState.value && !incompatibleVerdictLatched) {
                            publishRegisteredUiState(scheduleReconnect = true)
                        } else if (!incompatibleVerdictLatched && !rerunOnStateOn) {
                            publishServiceState(BluetoothState.ReadyDisconnected)
                        }
                    }
                } finally {
                    synchronized(capabilityCheckLock) {
                        if (capabilityCheckJob === coroutineContext[Job]) {
                            capabilityCheckJob = null
                        }
                    }
                    rerunAfterTransition()
                }
            }
            capabilityCheckJob = newJob
            newJob
        }
        jobToStart.start()
    }

    private suspend fun ensureHidReady(): BluetoothHidDevice? {
        if (incompatibleVerdictLatched) return null
        val hid = bindHidProxy() ?: return null
        if (ensureRegistered(hid) && appRegistrationState.value) return hid
        // Late callbacks may still restore readiness, but must not trap a Connect
        // request forever or make subsequent taps no-ops.
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun bindHidProxy(): BluetoothHidDevice? {
        rerunOnStateOn = false
        hidDeviceProfile?.let { return it }
        if (adapterPower() != AdapterPower.ON) return null
        val cycle = adapterCycle.get()
        var lastFailure = HidFailure.BINDING_REJECTED

        for (attempt in 1..retryPolicy.maxAttempts) {
            _lifecycleState.value = HidLifecycleState.BindingProxy(attempt)
            val binding = bindingMutex.withLock {
                hidDeviceProfile?.let { return@withLock CompletableDeferred(it) }
                pendingProxyBinding ?: CompletableDeferred<BluetoothHidDevice?>().also { deferred ->
                    pendingProxyBinding = deferred
                    val accepted = try {
                        bluetoothAdapter?.getProfileProxy(
                            context,
                            profileListener,
                            BluetoothProfile.HID_DEVICE,
                        ) == true
                    } catch (e: Throwable) {
                        Log.w("BluetoothKeyboard", "getProfileProxy attempt $attempt failed", e)
                        false
                    }
                    if (!accepted) deferred.complete(null)
                }
            }

            val proxy = withTimeoutOrNull(PROXY_CALLBACK_TIMEOUT_MILLIS) { binding.await() }
            if (proxy != null) return proxy
            lastFailure = if (binding.isCompleted) {
                HidFailure.BINDING_REJECTED
            } else {
                HidFailure.BINDING_TIMEOUT
            }
            bindingMutex.withLock {
                if (pendingProxyBinding === binding) pendingProxyBinding = null
            }
            if (attempt < retryPolicy.maxAttempts) {
                delay(retryPolicy.delayMillis(attempt, Random.nextDouble(-1.0, 1.0)))
            }
        }

        if (deferToStateOn(cycle)) return null
        _lifecycleState.value = HidLifecycleState.Error(lastFailure)
        val failureMessage = when (lastFailure) {
            HidFailure.BINDING_TIMEOUT -> "Bluetooth HID service did not respond. Try toggling Bluetooth."
            else -> "This device appears incompatible: Android repeatedly rejected the Bluetooth HID Device profile."
        }
        if (lastFailure.indicatesLikelyDeviceIncompatibility()) {
            confirmIncompatibleVerdict(failureMessage, lastFailure)
        } else {
            _statusMessage.value = failureMessage
        }
        return null
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            logHidEvent("callback proxy connected profile=$profile proxy=${System.identityHashCode(proxy)}")
            if (profile == BluetoothProfile.HID_DEVICE) {
                if (closed || bluetoothAdapter?.isEnabled != true) {
                    bluetoothAdapter?.closeProfileProxy(profile, proxy)
                    return
                }
                val hid = proxy as BluetoothHidDevice
                val current = hidDeviceProfile
                if (current != null && current !== hid) {
                    // A bind left over from before an off/on transition: registration already runs on the live proxy.
                    bluetoothAdapter?.closeProfileProxy(profile, proxy)
                    return
                }
                hidDeviceProfile = hid
                pendingProxyBinding?.complete(hid)
                pendingProxyBinding = null
                Log.d("BluetoothKeyboard", "HID Device profile proxy obtained — firmware supports HID peripheral role")
                if (incompatibleVerdictLatched) return

                // Attempt to restore connected state from active proxy connections before we unregister
                try {
                    val connectedDevs = hid.connectedDevices
                    val activeDev = connectedDevs?.firstOrNull()
                    if (activeDev != null) {
                        publishConnectedHost(activeDev)
                        // We intentionally DO NOT call connectDevice() here.
                        // We must wait for registerApp() to complete. 
                        // onAppStatusChanged(true) will seamlessly pick up lastConnectedDeviceAddress and connect.
                    }
                } catch (e: Exception) {
                    Log.e("BluetoothKeyboard", "Error restoring connected devices", e)
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            logHidEvent("callback proxy disconnected profile=$profile")
            if (closed) return
            if (profile == BluetoothProfile.HID_DEVICE) {
                activeRegistrationAttempt = null
                registrationCommandAccepted = false
                hidDeviceProfile = null
                appRegistrationState.value = false
                connectionEpoch.incrementAndGet()
                _connectedDevice.value = null
                // Bluetooth turning off unbinds the proxy too; STATE_ON binds again, so there is nothing to rebind now.
                if (adapterPower() != AdapterPower.ON) return
                _lifecycleState.value = HidLifecycleState.Error(HidFailure.BINDING_TIMEOUT)
                publishServiceState(BluetoothState.ReadyDisconnected)
                _statusMessage.value = "HID Service Proxy disconnected. Rebinding..."
                initProfileListener()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishRegisteredUiState(
        scheduleReconnect: Boolean,
    ): Unit = synchronized(connectionSelectionLock) {
        if (closed || incompatibleVerdictLatched) return
        if (!appRegistrationState.value || hidDeviceProfile == null) {
            _connectedDevice.value = null
            if (_lifecycleState.value !is HidLifecycleState.Registering &&
                _lifecycleState.value !is HidLifecycleState.BindingProxy) {
                _lifecycleState.value = HidLifecycleState.Error(lastRegistrationFailure ?: HidFailure.REGISTRATION_TIMEOUT)
                publishServiceState(BluetoothState.ReadyDisconnected)
            }
            return
        }
        updateBondedDevices()
        val connectedDevs = try {
            hidDeviceProfile?.connectedDevices
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error restoring connected devices", e)
            _lifecycleState.value = HidLifecycleState.Error(HidFailure.BINDING_TIMEOUT)
            _connectedDevice.value = null
            publishServiceState(BluetoothState.ReadyDisconnected)
            return
        }
        val activeDev = connectedDevs?.firstOrNull()
        if (activeDev != null) {
            publishConnectedHost(activeDev)
            return
        }

        if (_hasPendingConnection.value) return

        _lifecycleState.value = HidLifecycleState.Registered
        _connectedDevice.value = null
        _statusMessage.value = "Custom HID Deck is ready and advertising."
        if (!publishServiceState(
            BluetoothState.PairingMode(
                bluetoothAdapter?.name ?: context.getString(R.string.app_name),
            ),
        )) return

        if (!scheduleReconnect || automaticReconnectAttempted || suppressIncomingConnection ||
            sessionOperationJob?.isActive == true || connectRequestProcessor.pending.value != null) return
        val isAutoConnectEnabled = appPreferences.getBoolean("auto_connect", true)
        if (!isAutoConnectEnabled) return
        lastConnectedDeviceAddress?.let { address ->
            try {
                val lastDevice = bluetoothAdapter?.getRemoteDevice(address)
                if (lastDevice != null && lastDevice.bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d(
                        "BluetoothKeyboard",
                        "Scheduling auto-reconnect to last connected device: ${lastDevice.name ?: address}",
                    )
                    // Queue the delay as part of the latest-wins request, so a manual
                    // host selection cancels it instead of being overwritten 600 ms later.
                    automaticReconnectAttempted = true
                    connectDevice(lastDevice, delayMs = 600, automatic = true)
                }
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Failed to schedule auto-reconnect to last connected device", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishConnectedHost(device: BluetoothDevice): Unit = synchronized(connectionSelectionLock) {
        val pending = connectRequestProcessor.pending.value
        if (closed || incompatibleVerdictLatched || !appRegistrationState.value ||
            !canAdoptIncomingHost(device.address, _connectionTargetAddress.value,
                _hasPendingConnection.value && pending?.automatic != true,
                suppressIncomingConnection)) return
        if (hidDeviceProfile?.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) return
        if (pending?.automatic == true) {
            connectRequestProcessor.clear()
            _hasPendingConnection.value = false
        }
        val isNewLink = _connectedDevice.value?.address != device.address
        if (isNewLink) {
            connectionEpoch.incrementAndGet()
            lastInputReports.clear() // A new host's GET_REPORT sees idle input, not the last host's.
        }
        automaticReconnectAttempted = true
        recordSupportedFirmware()
        _connectionTargetAddress.value = if (_hasPendingConnection.value) device.address else null
        _connectedDevice.value = device
        _lastConnectionFailure.value = null
        lastConnectedDevice = device
        lastConnectedDeviceAddress = device.address
        _lifecycleState.value = HidLifecycleState.Connected(device.address)
        if (!publishServiceState(BluetoothState.Connected(device.name ?: "Paired Host"))) return
        _statusMessage.value = "Link established with '${device.name ?: "Host"}'! Keyboard active."
        if (isNewLink) resetKeyboardState()
        updateBondedDevices()
        if (appPreferences.getBoolean(PREF_DISCONNECT_AUDIO_PROFILES, false) && !audioProfilesDisconnectedForSession) {
            audioProfilesDisconnectedForSession = true
            disconnectAudioProfiles(device)
        }
    }

    private fun createHidCallback(attempt: HidRegistrationAttempt) = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            val expectedTeardown = attempt.state.value == HidRegistrationAttempt.State.UNREGISTERING
            if (activeRegistrationAttempt !== attempt || !attempt.statusChanged(registered)) {
                logHidEvent("ignored registration callback from retired/inactive attempt registered=$registered")
                return
            }
            logHidEvent("callback registration registered=$registered device=${pluggedDevice?.address} commandAccepted=$registrationCommandAccepted")
            if (closed) return
            if (bluetoothAdapter?.isEnabled != true) return
            
            DeveloperLogManager.log("BluetoothKeyboard", "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.address}")

            if (incompatibleVerdictLatched) {
                publishServiceState(BluetoothState.ProfileNotSupported)
                _statusMessage.value = incompatibleVerdictMessage
                    ?: "This device appears incompatible with the Bluetooth HID Device role."
                return
            }
            appRegistrationState.value = registered
            if (registered) recordSupportedFirmware()
            if (!registered) registrationCommandAccepted = false
            if (registered) {
                if (sessionOperationJob?.isActive == true) return
                if (isCapabilityCheckRunning()) return
                publishRegisteredUiState(scheduleReconnect = true)
            } else {
                connectionEpoch.incrementAndGet()
                _connectedDevice.value = null
                audioProfilesDisconnectedForSession = false
                if (!expectedTeardown && !registrationMutex.isLocked) {
                    connectRequestProcessor.clear()
                    _hasPendingConnection.value = false
                    _connectionTargetAddress.value = null
                    _lifecycleState.value = HidLifecycleState.Error(HidFailure.REGISTRATION_TIMEOUT)
                    _statusMessage.value = "HID registration was lost. Tap Restart HID Service to recover."
                    publishServiceState(BluetoothState.ReadyDisconnected)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            super.onConnectionStateChanged(device, state)
            if (activeRegistrationAttempt !== attempt) return
            logHidEvent("callback connection device=${device.address} state=$state reason=not-provided-by-public-api")
            if (closed) return
            val linkState = when (state) {
                BluetoothProfile.STATE_CONNECTED -> HostLinkState.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> HostLinkState.CONNECTING
                BluetoothProfile.STATE_DISCONNECTING -> HostLinkState.DISCONNECTING
                else -> HostLinkState.DISCONNECTED
            }
            connectionCoordinator.onStateChanged(device.address, linkState)
            DeveloperLogManager.log("BluetoothKeyboard", "connection device=${device.address} state=$linkState target=${_connectionTargetAddress.value}")
            if (incompatibleVerdictLatched) return
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    synchronized(connectionSelectionLock) {
                        // Observe Settings/host-initiated attempts without issuing any command
                        // or taking ownership away from an explicit in-app request.
                        if (!_hasPendingConnection.value && !suppressIncomingConnection &&
                            appRegistrationState.value && _connectedDevice.value == null &&
                            hidDeviceProfile?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTING) {
                            _lifecycleState.value = HidLifecycleState.Connecting(device.address)
                            _statusMessage.value = "Connecting to '${device.name ?: device.address}'..."
                        }
                    }
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    publishConnectedHost(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(connectionSelectionLock) {
                    if (_connectedDevice.value == null && !_hasPendingConnection.value &&
                        !suppressIncomingConnection && appRegistrationState.value &&
                        _lifecycleState.value == HidLifecycleState.Connecting(device.address) &&
                        hidDeviceProfile?.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                        _lifecycleState.value = HidLifecycleState.Error(HidFailure.CONNECTION_REJECTED)
                        _lastConnectionFailure.value =
                            LastConnectionFailure(ConnectionFailure.CONNECTION_REJECTED, device.address)
                        _statusMessage.value = "The incoming connection did not complete. See the connection help below."
                        return
                    }
                    if (!shouldClearConnectedHost(_connectedDevice.value?.address, device.address)) return
                    connectionEpoch.incrementAndGet()
                    _connectedDevice.value = null
                    audioProfilesDisconnectedForSession = false
                    resetKeyboardState()
                    if (_hasPendingConnection.value) return
                    _connectionTargetAddress.value = null
                    publishRegisteredUiState(scheduleReconnect = false)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            super.onSetReport(device, type, id, data)
            if (activeRegistrationAttempt !== attempt) return
            logHidEvent("callback setReport device=${device.address} type=$type id=$id length=${data.size}")
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                if (id == KEYBOARD_REPORT_ID.toByte()) {
                    parseLedReport(data)
                }
            }
            try {
                hidDeviceProfile?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Failed to send reportError success: $e")
            }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            super.onInterruptData(device, reportId, data)
            if (activeRegistrationAttempt !== attempt) return
            logHidEvent("callback interruptData device=${device.address} id=$reportId length=${data.size}")
            if (reportId == KEYBOARD_REPORT_ID.toByte()) {
                parseLedReport(data)
            }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            super.onSetProtocol(device, protocol)
            if (activeRegistrationAttempt !== attempt) return
            logHidEvent("callback setProtocol device=${device.address} protocol=$protocol (0=BOOT,1=REPORT)")
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            if (activeRegistrationAttempt !== attempt) return
            logHidEvent("callback getReport device=${device.address} type=$type id=$id bufferSize=$bufferSize")
            val reply = replyToGetReport(type, id.toInt(), lastInputReports::get, ledOutputByte, gamepadDpadOutputMode)
            try {
                val accepted = when (reply) {
                    is GetReportReply.Report -> hidDeviceProfile?.replyReport(device, type, id, reply.data)
                    is GetReportReply.Error -> hidDeviceProfile?.reportError(device, reply.code)
                }
                logHidEvent("result getReport reply=${reply.javaClass.simpleName} accepted=$accepted")
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Failed to answer getReport: $e")
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            super.onVirtualCableUnplug(device)
            if (activeRegistrationAttempt !== attempt) return
            logHidEvent("callback virtualCableUnplug device=${device.address}")
        }

        private fun parseLedReport(data: ByteArray?) {
            if (data == null || data.isEmpty()) return
            val ledByte = if (data.size > 1 && data[0] == 1.toByte()) {
                data[1].toInt()
            } else {
                data[0].toInt()
            }
            ledOutputByte = ledByte.toByte()
            _numLockState.value = (ledByte and 0x01) != 0
            numLockTogglePending.set(false)
            _capsLockState.value = (ledByte and 0x02) != 0
            _scrollLockState.value = (ledByte and 0x04) != 0
            Log.d("BluetoothKeyboard", "Received LED report: byte=$ledByte, caps=${_capsLockState.value}, num=${_numLockState.value}, scroll=${_scrollLockState.value}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        if (incompatibleVerdictLatched) return
        _statusMessage.value = "Checking Bluetooth HID Device compatibility..."
        launchCapabilityCheck()
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureRegistered(hid: BluetoothHidDevice, forceReset: Boolean = false): Boolean =
        registrationMutex.withLock {
            if (closed || incompatibleVerdictLatched || (!appInForeground && !_hasPendingConnection.value)) return@withLock false
            if (appRegistrationState.value && !forceReset) return@withLock true
            val cycle = adapterCycle.get()
            rerunOnStateOn = false
            lastRegistrationFailure = null
            _lifecycleState.value = HidLifecycleState.Registering(1)
            val registrationMode = gamepadDpadOutputMode
            val settings = sdpSettings(registrationMode)
            if (settings == null) {
                confirmIncompatibleVerdict(
                    "Bluetooth HID Device role is not supported on this device.",
                )
                return@withLock false
            }

            val facade = object : BluetoothRegistrationFacade {
                override val registrationState: StateFlow<Boolean> = appRegistrationState

                override suspend fun unregisterApp(): Boolean {
                    val previous = activeRegistrationAttempt
                    val needsConfirmation = needsConfirmedUnregistration(previous?.state?.value)
                    val accepted = withContext(callbackDispatcher) {
                      try {
                        previous?.beginUnregister()
                        appRegistrationState.value = false
                        logHidEvent("command unregister reason=registration-cleanup forceReset=$forceReset")
                        val accepted = hid.unregisterApp()
                        logHidEvent("result unregister accepted=$accepted")
                        accepted
                      } catch (e: Exception) {
                        Log.w("BluetoothKeyboard", "HID registration cleanup failed", e)
                        false
                      }
                    }
                    if (needsConfirmation && previous != null) {
                        if (!accepted && previous.state.value != HidRegistrationAttempt.State.UNREGISTERED) return false
                        val confirmed = withTimeoutOrNull(PROXY_CALLBACK_TIMEOUT_MILLIS) {
                            previous.state.first { it == HidRegistrationAttempt.State.UNREGISTERED }
                        } != null
                        if (!confirmed || activeRegistrationAttempt !== previous || hid !== hidDeviceProfile) return false
                    }
                    // Retire the callback before a replacement registration can start.
                    withContext(callbackDispatcher) {
                        if (activeRegistrationAttempt === previous) activeRegistrationAttempt = null
                        registrationCommandAccepted = false
                        appRegistrationState.value = false
                    }
                    return true
                }

                override suspend fun registerApp(): Boolean = withContext(callbackDispatcher) {
                    if (closed || hid !== hidDeviceProfile || bluetoothAdapter?.isEnabled != true) return@withContext false
                    registrationCommandAccepted = false
                    val attempt = HidRegistrationAttempt()
                    activeRegistrationAttempt = attempt
                    if (DeveloperLogManager.isEnabled) {
                        val descriptor = hidDescriptorForMode(registrationMode)
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(descriptor)
                            .joinToString("") { "%02x".format(it) }
                        logHidEvent("command register requestedMode=$registrationMode descriptorLength=${descriptor.size} descriptorSha256=$hash descriptor=[${descriptor.joinToString(" ") { "%02X".format(it) }}]")
                    }
                    val accepted = try {
                        hid.registerApp(settings, null, null, executor, createHidCallback(attempt))
                    } catch (e: Exception) {
                        Log.w("BluetoothKeyboard", "registerApp failed", e)
                        false
                    }
                    registrationCommandAccepted = accepted
                    attempt.commandCompleted(accepted)
                    logHidEvent("result register accepted=$accepted")
                    accepted
                }
            }
            val result = HidRegistrationCoordinator(
                facade = facade,
                retryPolicy = retryPolicy,
                initialCleanupDelayMillis = STALE_REGISTRATION_SETTLE_MILLIS,
            ).register(forceReset = forceReset) { attempt ->
                _lifecycleState.value = HidLifecycleState.Registering(attempt)
                _statusMessage.value =
                    "Registering Bluetooth HID application profile (attempt $attempt)..."
            }

            when (result) {
                RegistrationResult.TeardownFailed -> {
                    if (deferToStateOn(cycle)) return@withLock false
                    lastRegistrationFailure = HidFailure.REGISTRATION_TIMEOUT
                    _lifecycleState.value = HidLifecycleState.Error(HidFailure.REGISTRATION_TIMEOUT)
                    _statusMessage.value = "HID cleanup did not finish. Retry Restart HID Service or toggle Bluetooth."
                    publishServiceState(BluetoothState.ReadyDisconnected)
                    return@withLock false
                }
                is RegistrationResult.Registered -> {
                    recordSupportedFirmware()
                    lastRegistrationFailure = null
                    _lifecycleState.value = HidLifecycleState.Registered
                    return@withLock true
                }
                is RegistrationResult.TimedOut -> {
                    lastRegistrationFailure = HidFailure.REGISTRATION_TIMEOUT
                    Log.w(
                        "BluetoothKeyboard",
                        "No onAppStatusChanged(true) within ${result.timeoutMillis}ms; capability remains inconclusive",
                    )
                }
                is RegistrationResult.Rejected -> {
                    lastRegistrationFailure = HidFailure.REGISTRATION_REJECTED
                }
            }

            if (deferToStateOn(cycle)) return@withLock false
            val lastFailure = lastRegistrationFailure ?: HidFailure.REGISTRATION_REJECTED
            _lifecycleState.value = HidLifecycleState.Error(lastFailure)
            publishServiceState(BluetoothState.ReadyDisconnected)
            val failureMessage = if (lastFailure == HidFailure.REGISTRATION_TIMEOUT) {
                "HID registration callback timed out; support is inconclusive. Try toggling Bluetooth."
            } else {
                "This device appears incompatible: Android repeatedly rejected HID Device registration."
            }
            if (lastFailure.indicatesLikelyDeviceIncompatibility()) {
                confirmIncompatibleVerdict(failureMessage, lastFailure)
            } else {
                _statusMessage.value = failureMessage
            }
            false
        }

    @SuppressLint("MissingPermission")
    fun restartHidService(): Unit = synchronized(connectionSelectionLock) {
        logHidEvent("request restartHidService")
        if (closed || incompatibleVerdictLatched) return
        if (sessionOperationJob?.isActive == true) return
        val capabilityJob = synchronized(capabilityCheckLock) {
            capabilityCheckGeneration.incrementAndGet()
            capabilityCheckJob.also { it?.cancel(); capabilityCheckJob = null }
        }
        connectRequestProcessor.clear()
        _connectionTargetAddress.value = null
        _lastConnectionFailure.value = null
        _hasPendingConnection.value = true
        connectionEpoch.incrementAndGet()
        suppressIncomingConnection = true
        _lifecycleState.value = HidLifecycleState.Registering(1)
        _statusMessage.value = "Restarting local HID Service..."
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            try {
                capabilityJob?.join()
                val hid = bindHidProxy() ?: return@launch
                if (!connectionCoordinator.disconnectAll()) {
                    _lifecycleState.value = HidLifecycleState.Error(HidFailure.REGISTRATION_TIMEOUT)
                    _statusMessage.value = "The host did not disconnect for recovery. Retry or toggle Bluetooth."
                    return@launch
                }
                ensureRegistered(hid, forceReset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BluetoothKeyboard", "HID recovery failed", e)
                _lifecycleState.value = HidLifecycleState.Error(HidFailure.REGISTRATION_TIMEOUT)
                _statusMessage.value = "HID recovery failed. Retry or toggle Bluetooth."
            } finally {
                synchronized(connectionSelectionLock) {
                    suppressIncomingConnection = false
                    _hasPendingConnection.value = false
                    if (sessionOperationJob === coroutineContext[Job]) sessionOperationJob = null
                    if (rerunOnStateOn) {
                        rerunAfterTransition()
                    } else if (!closed && bluetoothAdapter?.isEnabled == true) {
                        if (_lifecycleState.value is HidLifecycleState.Error) {
                            _connectedDevice.value = null
                            publishServiceState(BluetoothState.ReadyDisconnected)
                        } else {
                            publishRegisteredUiState(scheduleReconnect = false)
                        }
                    }
                }
            }
        }
        sessionOperationJob = job
        job.start()
    }

    @SuppressLint("MissingPermission")
    fun sendKey(keyCode: Int, isPress: Boolean) {
        val dev = _connectedDevice.value
        
        val report = ByteArray(KEYBOARD_REPORT_SIZE)
        synchronized(activeKeys) {
            // Update local HID state variables (Modifiers or standard key codes)
            if (keyCode in 0xE0..0xE7) {
                // It's a modifier key (Left Ctrl to Right GUI)
                val bitMask = 1 shl (keyCode - 0xE0)
                activeModifiers = if (isPress) {
                    activeModifiers or bitMask
                } else {
                    activeModifiers and bitMask.inv()
                }
            } else {
                // It's a standard key
                if (isPress) {
                    // Find empty slot (0x00) or check if already placed
                    var placed = false
                    for (j in 0 until 6) {
                        if (activeKeys[j] == keyCode.toByte()) {
                            placed = true
                            break
                        }
                    }
                    if (!placed) {
                        for (j in 0 until 6) {
                            if (activeKeys[j] == 0.toByte()) {
                                activeKeys[j] = keyCode.toByte()
                                break
                            }
                        }
                    }
                } else {
                    // Key release: remove from slots and shift left
                    for (j in 0 until 6) {
                        if (activeKeys[j] == keyCode.toByte()) {
                            activeKeys[j] = 0.toByte()
                        }
                    }
                    // Compact active keys
                    val compact = ByteArray(6)
                    var writeIdx = 0
                    for (j in 0 until 6) {
                        if (activeKeys[j] != 0.toByte()) {
                            compact[writeIdx++] = activeKeys[j]
                        }
                    }
                    compact.copyInto(activeKeys)
                }
            }

            keyboardReportLocked().copyInto(report)
        }

        // Transmit HID report
        if (dev != null) {
            submitReport(dev, KEYBOARD_REPORT_ID, report)
        }
    }

    /** Modifiers, a reserved byte, then the six held key codes. Call holding the activeKeys lock. */
    private fun keyboardReportLocked(): ByteArray {
        val report = ByteArray(KEYBOARD_REPORT_SIZE)
        report[0] = activeModifiers.toByte()
        activeKeys.copyInto(report, destinationOffset = 2)
        return report
    }

    /**
     * Someone is about to give input (a finger landed on the touchpad): wake the link now, before the
     * first real report, and hold it awake as [LinkKeepAlive] describes.
     */
    fun holdLinkAwake() {
        lastInputAt = android.os.SystemClock.uptimeMillis()
        startKeepAlive()
        sendKeepAlive()
    }

    private fun sendKeepAlive() {
        val dev = _connectedDevice.value ?: return
        // Queued under the key lock, so a keepalive never lands after a newer key report with older state.
        synchronized(activeKeys) { submitReport(dev, KEYBOARD_REPORT_ID, keyboardReportLocked(), isKeepAlive = true) }
    }

    private fun startKeepAlive() = synchronized(keepAliveLock) {
        if (keepAliveTask?.isDone == false || closed) return
        keepAliveTask = try {
            executor.scheduleWithFixedDelay(::keepAliveTick, LinkKeepAlive.CHECK_MILLIS, LinkKeepAlive.CHECK_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null // closed meanwhile
        }
    }

    private fun keepAliveTick() {
        val now = android.os.SystemClock.uptimeMillis()
        if (!LinkKeepAlive.active(now, lastInputAt)) {
            synchronized(keepAliveLock) {
                if (!LinkKeepAlive.active(android.os.SystemClock.uptimeMillis(), lastInputAt)) keepAliveTask?.cancel(false)
            }
            return
        }
        if (LinkKeepAlive.due(now, lastInputAt, lastSentAt)) sendKeepAlive()
    }

    @SuppressLint("MissingPermission")
    fun sendMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte, pan: Byte = 0) {
        val dev = _connectedDevice.value
        if (dev != null) {
            submitReport(dev, MOUSE_REPORT_ID, buildMouseReport(buttons, x, y, wheel, pan))
        }
    }

    @SuppressLint("MissingPermission")
    fun sendGamepadReport(
        buttonMask: Int,
        dpadMask: Int,
        leftXFloat: Float,
        leftYFloat: Float,
        rightXFloat: Float,
        rightYFloat: Float
    ) {
        if (!appRegistrationState.value) return
        val dev = _connectedDevice.value
        if (dev != null) {
            val report = buildGamepadReport(
                buttonMask,
                dpadMask,
                leftXFloat,
                leftYFloat,
                rightXFloat,
                rightYFloat,
                gamepadDpadOutputMode,
            )
            submitReport(dev, GAMEPAD_REPORT_ID, report)
        }
    }



    fun resetKeyboardState() {
        synchronized(activeKeys) {
            activeModifiers = 0
            activeKeys.fill(0)
        }
        numLockTogglePending.set(false)
    }

    /** Press or release a numpad key. A keypad digit first turns on the host's Num Lock if it is off. */
    fun sendNumpadKey(legend: String, isPress: Boolean) {
        val stroke = numpadStroke(legend) ?: return
        if (isPress && stroke.needsNumLock && !_numLockState.value &&
            numLockTogglePending.compareAndSet(false, true)
        ) {
            sendKey(KEY_NUM_LOCK, true)
            sendKey(KEY_NUM_LOCK, false)
        }
        if (isPress && stroke.shift) sendKey(KEY_LEFT_SHIFT, true)
        sendKey(stroke.keyCode, isPress)
        if (!isPress && stroke.shift) sendKey(KEY_LEFT_SHIFT, false)
    }

    /** Release input without tearing down the Bluetooth session when its UI leaves foreground. */
    fun releaseAllInputs() {
        connectionEpoch.incrementAndGet() // Discard held-input reports that have not been sent yet.
        resetKeyboardState()
        _connectedDevice.value?.let { device ->
            submitReport(device, KEYBOARD_REPORT_ID, ByteArray(KEYBOARD_REPORT_SIZE))
            sendMouseReport(0, 0, 0, 0)
            sendGamepadReport(0, 0, 0f, 0f, 0f, 0f)
            sendConsumerControl(null)
        }
        lastInputAt = Long.MIN_VALUE / 2 // Bluke is going out of use: let the link sleep.
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAudioProfiles(device: BluetoothDevice) {
        val adapter = bluetoothAdapter ?: return
        
        managerScope.launch {
            // Linux/Arch hosts often initiate A2DP audio connections asynchronously *after* HID connects.
            // We do 3 aggressive sweeps over 4 seconds to abort any incoming or established audio links.
            for (i in 0..2) {
                delay(if (i == 0) 500L else 1500L) // Sweeps at 0.5s, 2.0s, 3.5s
                
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            // Blindly invoke disconnect to abort even if it's currently in a 'Connecting' state
                            val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            val success = disconnectMethod.invoke(proxy, device) as Boolean
                            Log.d("BluetoothKeyboard", "Sweep $i: Disconnected A2DP profile for host, success=$success")
                        } catch (e: Exception) {
                            Log.d("BluetoothKeyboard", "Sweep $i: No A2DP profile to disconnect or reflection failed.")
                        } finally {
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, BluetoothProfile.A2DP)

                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            val success = disconnectMethod.invoke(proxy, device) as Boolean
                            Log.d("BluetoothKeyboard", "Sweep $i: Disconnected Headset profile for host, success=$success")
                        } catch (e: Exception) {
                            Log.d("BluetoothKeyboard", "Sweep $i: No Headset profile to disconnect or reflection failed.")
                        } finally {
                            adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, BluetoothProfile.HEADSET)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        logHidEvent("manager close requested")
        synchronized(connectionSelectionLock) {
            if (closed) return
            closed = true
        }
        connectionEpoch.incrementAndGet()
        sessionOperationJob?.cancel()
        activeRegistrationAttempt = null
        connectRequestProcessor.clear()
        _hasPendingConnection.value = false
        _connectionTargetAddress.value = null
        managerJob.cancel()
        resetKeyboardState()
        appPreferences.unregisterOnSharedPreferenceChangeListener(behaviorPreferenceListener)
        stopScanning()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error unregistering receiver", e)
            }
            isReceiverRegistered = false
        }
        if (isBondReceiverRegistered) {
            try {
                context.unregisterReceiver(bondStateReceiver)
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error unregistering bond receiver", e)
            }
            isBondReceiverRegistered = false
        }
        val hid = hidDeviceProfile
        if (hid != null) {
            try {
                logHidEvent("command unregister reason=manager-close")
                val accepted = hid.unregisterApp()
                logHidEvent("result unregister reason=manager-close accepted=$accepted")
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error during app unregistration", e)
            }
        }
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error closing profile proxy", e)
        }
        hidDeviceProfile = null
        registrationCommandAccepted = false
        appRegistrationState.value = false
        _lifecycleState.value = HidLifecycleState.Idle
        lastConnectedDevice = null
        _connectedDevice.value = null
        managerJob.cancel()
        executor.shutdownNow()
        reportExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        close()
    }

    @SuppressLint("MissingPermission")
    fun sendConsumerControl(control: ConsumerControl?) {
        val dev = _connectedDevice.value
        if (dev != null) {
            submitReport(dev, CONSUMER_REPORT_ID, buildConsumerControlReport(control))
        }
    }
}
