package dev.arnv.bluke.ui

/** A [SurfaceChrome] for tests and screenshots: no-op actions and the given host. */
internal fun previewChrome(
    mode: InputMode,
    host: SurfaceHost = SurfaceHost(SurfaceHostStatus.Offline, null),
    onClose: () -> Unit = {},
    onNextMode: () -> Unit = {},
): SurfaceChrome = SurfaceChrome(mode = mode, host = host, onClose = onClose, onNextMode = onNextMode)
