package dev.arnv.bluke.bluetooth

import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

sealed interface HidLifecycleState {
    data object Idle : HidLifecycleState
    data class BindingProxy(val attempt: Int) : HidLifecycleState
    data class Registering(val attempt: Int) : HidLifecycleState
    data object Registered : HidLifecycleState
    data class Connecting(val deviceAddress: String) : HidLifecycleState
    data class Connected(val deviceAddress: String) : HidLifecycleState
    data class Error(val failure: HidFailure) : HidLifecycleState
}

enum class HidFailure {
    BINDING_REJECTED,
    BINDING_TIMEOUT,
    REGISTRATION_REJECTED,
    REGISTRATION_TIMEOUT,
    CONNECTION_REJECTED,
}

internal fun shouldDiagnoseHidIncompatibility(
    failure: HidFailure,
    previouslyRegistered: Boolean,
    appInForeground: Boolean,
): Boolean = appInForeground && !previouslyRegistered &&
    failure.indicatesLikelyDeviceIncompatibility()

/**
 * A synchronous rejection that persists through the bounded retry policy is the best signal
 * Android exposes for firmware that does not provide a usable HID Device role. Callback timeouts
 * remain inconclusive because some OEM stacks acknowledge a successful command late or not at all.
 */
internal fun HidFailure.indicatesLikelyDeviceIncompatibility(): Boolean = when (this) {
    HidFailure.BINDING_REJECTED,
    HidFailure.REGISTRATION_REJECTED -> true
    HidFailure.BINDING_TIMEOUT,
    HidFailure.REGISTRATION_TIMEOUT,
    HidFailure.CONNECTION_REJECTED -> false
}

internal fun isRegistrationCallbackActionable(
    registered: Boolean,
    registrationCommandAccepted: Boolean,
): Boolean = !registered || registrationCommandAccepted

internal fun shouldBlockServiceStatePublication(
    incompatibleVerdictLatched: Boolean,
    publishingIncompatibleVerdict: Boolean,
    capabilityCheckRunning: Boolean,
    currentStateIsCapabilityCheck: Boolean,
    publishingOperationalState: Boolean,
): Boolean =
    (incompatibleVerdictLatched && !publishingIncompatibleVerdict) ||
        (capabilityCheckRunning && currentStateIsCapabilityCheck && publishingOperationalState)

/** Where the phone's Bluetooth adapter is in its power cycle, read from `BluetoothAdapter.getState()`. */
enum class AdapterPower {
    OFF, TURNING_ON, ON, TURNING_OFF;

    companion object {
        /** The BLE-only states read as off: classic Bluetooth, and so HID, is down in them. */
        fun of(state: Int): AdapterPower = when (state) {
            BluetoothAdapter.STATE_ON -> ON
            BluetoothAdapter.STATE_TURNING_ON -> TURNING_ON
            BluetoothAdapter.STATE_TURNING_OFF -> TURNING_OFF
            else -> OFF
        }
    }
}

/**
 * What the manager may publish when a step asks for [requested]: while the adapter is anything but on,
 * Bluetooth is off, whatever a proxy or registration step concluded. A missing adapter and missing
 * permissions still show, since they are what the user fixes first.
 */
internal fun publishableServiceState(adapter: AdapterPower, requested: BluetoothState): BluetoothState = when {
    adapter == AdapterPower.ON -> requested
    requested is BluetoothState.Unsupported || requested is BluetoothState.PermissionRequired -> requested
    else -> BluetoothState.BluetoothOff
}

enum class HidFailureResponse {
    /** The failure stands: it is published, and a rejection may become the incompatible verdict. */
    REPORT,

    /** The adapter's off/on transition caused it: registration starts again once the adapter is on. */
    RETRY_ON_STATE_ON,
}

/**
 * A binding or registration failure from an attempt the adapter left on during ([attemptAdapterCycle] is the
 * adapter cycle the attempt began in, [currentAdapterCycle] the one now) says nothing about the phone, so it
 * retries on STATE_ON. Only a failure with the adapter on throughout the attempt is reported, which keeps a
 * persistent rejection the signal for firmware without a usable HID Device role.
 */
internal fun hidFailureResponse(
    attemptAdapterCycle: Long,
    currentAdapterCycle: Long,
    adapter: AdapterPower,
): HidFailureResponse =
    if (adapter != AdapterPower.ON || attemptAdapterCycle != currentAdapterCycle) HidFailureResponse.RETRY_ON_STATE_ON
    else HidFailureResponse.REPORT

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 300,
    val maxDelayMillis: Long = 2_000,
    val jitterRatio: Double = 0.25,
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMillis >= 0)
        require(maxDelayMillis >= initialDelayMillis)
        require(jitterRatio in 0.0..1.0)
    }

    fun delayMillis(attempt: Int, jitter: Double): Long {
        require(attempt > 0)
        require(jitter in -1.0..1.0)
        val multiplier = 1L shl min(attempt - 1, 30)
        val exponential = min(maxDelayMillis, initialDelayMillis * multiplier)
        return (exponential * (1.0 + jitterRatio * jitter)).toLong().coerceAtLeast(0)
    }
}

sealed interface BluetoothCapability {
    data object Checking : BluetoothCapability
    data object Available : BluetoothCapability
    data object RegistrationRejected : BluetoothCapability
    data class Inconclusive(val timeoutMillis: Long) : BluetoothCapability
}

class BluetoothCapabilityRepository(
    private val registrationState: StateFlow<Boolean>,
    private val requestRegistration: suspend () -> Boolean,
    private val callbackTimeoutMillis: Long = REGISTRATION_CALLBACK_TIMEOUT_MILLIS,
) {
    fun capability(): Flow<BluetoothCapability> = flow {
        emit(BluetoothCapability.Checking)
        if (registrationState.value) {
            emit(BluetoothCapability.Available)
            return@flow
        }

        if (!requestRegistration()) {
            emit(BluetoothCapability.RegistrationRejected)
            return@flow
        }

        val registered = withTimeoutOrNull(callbackTimeoutMillis) {
            registrationState.first { it }
        }
        emit(
            if (registered == true) BluetoothCapability.Available
            else BluetoothCapability.Inconclusive(callbackTimeoutMillis)
        )
    }

    companion object {
        // Field reports only establish this as a weak baseline, not proof of unsupported firmware.
        const val REGISTRATION_CALLBACK_TIMEOUT_MILLIS = 8_000L
    }
}
