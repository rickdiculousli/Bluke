package dev.arnv.bluke.ui

import dev.arnv.bluke.bluetooth.CURRENT_HID_DESCRIPTOR_REVISION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiPolicyTest {
    @Test
    fun gamepadGuideShowsOnlyBeforeTheFirstGamepadLaunch() {
        assertTrue(shouldShowGamepadGuide(InputMode.GAMEPAD, guideSeen = false))
        assertFalse(shouldShowGamepadGuide(InputMode.GAMEPAD, guideSeen = true))
        assertFalse(shouldShowGamepadGuide(InputMode.KEYBOARD, guideSeen = false))
        assertFalse(shouldShowGamepadGuide(InputMode.TOUCHPAD, guideSeen = false))
    }

    @Test
    fun aFreshInstallSeesNoLaunchNotice() {
        assertEquals(LaunchNotice.NONE, launchNotice(savedVersionCode = 0, hasSeenOnboarding = false, savedDescriptorRevision = 1))
    }

    @Test
    fun anAppUpdateAloneSaysNothing() {
        assertEquals(
            LaunchNotice.NONE,
            launchNotice(savedVersionCode = 3, hasSeenOnboarding = true, savedDescriptorRevision = CURRENT_HID_DESCRIPTOR_REVISION),
        )
    }

    @Test
    fun anUpgradeAcrossADescriptorChangeAsksToRepair() {
        assertEquals(LaunchNotice.DESCRIPTOR_REFRESH, launchNotice(savedVersionCode = 3, hasSeenOnboarding = false, savedDescriptorRevision = 1))
        assertEquals(LaunchNotice.DESCRIPTOR_REFRESH, launchNotice(savedVersionCode = 0, hasSeenOnboarding = true, savedDescriptorRevision = 1))
    }

    @Test
    fun grantAsksAndroidUntilItStopsShowingThePrompt() {
        // Never asked: Android shows the prompt even though no rationale is due yet.
        assertEquals(PermissionStep.REQUEST, permissionStep(askedBefore = false, deniedShowsRationale = listOf(false, false)))
        // Denied once: Android will ask again.
        assertEquals(PermissionStep.REQUEST, permissionStep(askedBefore = true, deniedShowsRationale = listOf(true, true)))
        // Denied twice: no prompt any more, so only app settings can grant it.
        assertEquals(PermissionStep.OPEN_APP_SETTINGS, permissionStep(askedBefore = true, deniedShowsRationale = listOf(false, false)))
        // Nothing denied: asking is harmless and re-checks.
        assertEquals(PermissionStep.REQUEST, permissionStep(askedBefore = true, deniedShowsRationale = emptyList()))
    }
}
