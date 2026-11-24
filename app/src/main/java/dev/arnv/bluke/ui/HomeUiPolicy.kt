package dev.arnv.bluke.ui

import dev.arnv.bluke.bluetooth.requiresHidDescriptorRefresh

internal fun shouldShowGamepadGuide(mode: InputMode, guideSeen: Boolean): Boolean =
    mode == InputMode.GAMEPAD && !guideSeen

/** What home says once at launch. Only a descriptor change needs words; an app update alone needs none. */
enum class LaunchNotice { NONE, DESCRIPTOR_REFRESH }

internal fun launchNotice(
    savedVersionCode: Int,
    hasSeenOnboarding: Boolean,
    savedDescriptorRevision: Int,
): LaunchNotice {
    // Older builds wrote these on first run; a fresh install has neither and pairs with today's descriptor.
    val existing = savedVersionCode > 0 || hasSeenOnboarding
    return if (requiresHidDescriptorRefresh(savedDescriptorRevision, existing)) LaunchNotice.DESCRIPTOR_REFRESH
    else LaunchNotice.NONE
}

/** What Grant permission does: ask Android, or, once Android has stopped showing the prompt, open app settings. */
enum class PermissionStep { REQUEST, OPEN_APP_SETTINGS }

/**
 * After a person denies a permission twice, Android stops showing the prompt and the request returns
 * denied at once; `shouldShowRequestPermissionRationale` is then false for it. Before the first request
 * it is false too, so [askedBefore] tells the two apart.
 */
internal fun permissionStep(askedBefore: Boolean, deniedShowsRationale: List<Boolean>): PermissionStep =
    if (askedBefore && deniedShowsRationale.isNotEmpty() && deniedShowsRationale.none { it }) PermissionStep.OPEN_APP_SETTINGS
    else PermissionStep.REQUEST

