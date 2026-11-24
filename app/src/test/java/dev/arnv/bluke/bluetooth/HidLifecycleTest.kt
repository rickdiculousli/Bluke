package dev.arnv.bluke.bluetooth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidLifecycleTest {
    @Test
    fun recoveryRejectionsDoNotInvalidateProvenSupport() {
        for (failure in HidFailure.entries) {
            assertFalse(shouldDiagnoseHidIncompatibility(failure, previouslyRegistered = true, appInForeground = true))
            assertFalse(shouldDiagnoseHidIncompatibility(failure, previouslyRegistered = false, appInForeground = false))
        }
        assertTrue(shouldDiagnoseHidIncompatibility(HidFailure.REGISTRATION_REJECTED, false, true))
        assertTrue(shouldDiagnoseHidIncompatibility(HidFailure.BINDING_REJECTED, false, true))
        assertFalse(shouldDiagnoseHidIncompatibility(HidFailure.REGISTRATION_TIMEOUT, false, true))
    }

    @Test
    fun positiveRegistrationCallbackRequiresAcceptedCommand() {
        assertFalse(isRegistrationCallbackActionable(registered = true, registrationCommandAccepted = false))
        assertTrue(isRegistrationCallbackActionable(registered = true, registrationCommandAccepted = true))
        assertTrue(isRegistrationCallbackActionable(registered = false, registrationCommandAccepted = false))
    }

    @Test
    fun terminalIncompatibilityAndActiveChecksBlockOperationalUiStates() {
        assertTrue(
            shouldBlockServiceStatePublication(
                incompatibleVerdictLatched = true,
                publishingIncompatibleVerdict = false,
                capabilityCheckRunning = false,
                currentStateIsCapabilityCheck = false,
                publishingOperationalState = true,
            )
        )
        assertTrue(
            shouldBlockServiceStatePublication(
                incompatibleVerdictLatched = false,
                publishingIncompatibleVerdict = false,
                capabilityCheckRunning = true,
                currentStateIsCapabilityCheck = true,
                publishingOperationalState = true,
            )
        )
        assertFalse(
            shouldBlockServiceStatePublication(
                incompatibleVerdictLatched = true,
                publishingIncompatibleVerdict = true,
                capabilityCheckRunning = false,
                currentStateIsCapabilityCheck = false,
                publishingOperationalState = false,
            )
        )
    }

    @Test
    fun onlySynchronousProfileRejectionsIndicateLikelyIncompatibility() {
        assertEquals(true, HidFailure.BINDING_REJECTED.indicatesLikelyDeviceIncompatibility())
        assertEquals(true, HidFailure.REGISTRATION_REJECTED.indicatesLikelyDeviceIncompatibility())
        assertEquals(false, HidFailure.BINDING_TIMEOUT.indicatesLikelyDeviceIncompatibility())
        assertEquals(false, HidFailure.REGISTRATION_TIMEOUT.indicatesLikelyDeviceIncompatibility())
        assertEquals(false, HidFailure.CONNECTION_REJECTED.indicatesLikelyDeviceIncompatibility())
    }

    @Test
    fun retryPolicy_appliesExponentialBackoffAndBoundedJitter() {
        val policy = RetryPolicy(initialDelayMillis = 400, maxDelayMillis = 1_600, jitterRatio = 0.25)

        assertEquals(300, policy.delayMillis(attempt = 1, jitter = -1.0))
        assertEquals(1_000, policy.delayMillis(attempt = 2, jitter = 1.0))
        assertEquals(1_600, policy.delayMillis(attempt = 4, jitter = 0.0))
    }

    @Test
    fun capability_reportsRejectedRegistrationCommand() = runTest {
        val repository = BluetoothCapabilityRepository(
            registrationState = MutableStateFlow(false),
            requestRegistration = { false },
        )

        assertEquals(
            listOf(BluetoothCapability.Checking, BluetoothCapability.RegistrationRejected),
            repository.capability().toList(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun capability_timeoutIsInconclusiveNotUnsupported() = runTest {
        val repository = BluetoothCapabilityRepository(
            registrationState = MutableStateFlow(false),
            requestRegistration = { true },
            callbackTimeoutMillis = 8_000,
        )

        val result = repository.capability().toList()
        advanceTimeBy(8_000)

        assertEquals(BluetoothCapability.Checking, result.first())
        assertEquals(BluetoothCapability.Inconclusive(8_000), result.last())
    }
    @Test
    fun adapterThatIsNotOnPublishesOnlyBluetoothOff() {
        val lifecycleSteps = listOf(
            BluetoothState.InitializingCapabilities,
            BluetoothState.CheckingCapabilities,
            BluetoothState.ReadyDisconnected,
            BluetoothState.ProfileNotSupported,
            BluetoothState.PairingMode("Phone"),
            BluetoothState.Connected("Mac"),
            BluetoothState.BluetoothOff,
        )
        for (adapter in listOf(AdapterPower.OFF, AdapterPower.TURNING_ON, AdapterPower.TURNING_OFF)) {
            for (state in lifecycleSteps) {
                assertEquals("$state while $adapter", BluetoothState.BluetoothOff, publishableServiceState(adapter, state))
            }
            assertEquals(BluetoothState.PermissionRequired, publishableServiceState(adapter, BluetoothState.PermissionRequired))
            assertEquals(BluetoothState.Unsupported, publishableServiceState(adapter, BluetoothState.Unsupported))
        }
        for (state in lifecycleSteps) assertEquals(state, publishableServiceState(AdapterPower.ON, state))
    }

    @Test
    fun adapterPowerReadsTheBleOnlyStatesAsOff() {
        assertEquals(AdapterPower.ON, AdapterPower.of(12))
        assertEquals(AdapterPower.TURNING_ON, AdapterPower.of(11))
        assertEquals(AdapterPower.TURNING_OFF, AdapterPower.of(13))
        for (state in listOf(10, 14, 15, 16, -1)) assertEquals(AdapterPower.OFF, AdapterPower.of(state))
    }

    @Test
    fun failureDuringTheOffOnTransitionRetriesOnStateOn() {
        // Seen while the adapter is still off or changing.
        for (adapter in listOf(AdapterPower.OFF, AdapterPower.TURNING_ON, AdapterPower.TURNING_OFF)) {
            assertEquals(HidFailureResponse.RETRY_ON_STATE_ON, hidFailureResponse(3, 3, adapter))
        }
        // Begun before Bluetooth went off, seen after it came back on.
        assertEquals(HidFailureResponse.RETRY_ON_STATE_ON, hidFailureResponse(3, 4, AdapterPower.ON))
        // With the adapter on throughout, a rejection stands, so the incompatible verdict still works.
        assertEquals(HidFailureResponse.REPORT, hidFailureResponse(4, 4, AdapterPower.ON))
        assertTrue(shouldDiagnoseHidIncompatibility(HidFailure.REGISTRATION_REJECTED, false, true))
    }
}
