package dev.arnv.bluke.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.BlukeApplication
import dev.arnv.bluke.QuickCycleActivity
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.mirror.MirrorFit
import dev.arnv.bluke.mirror.MirrorNotice
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorRotation
import dev.arnv.bluke.mirror.MirrorTouchFrame
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPoint
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.fitMirror
import kotlinx.coroutines.delay

/**
 * Where the surface gets its picture. [frameSeq] changes once per decoded frame and is read only
 * while drawing, so a new frame redraws the image without recomposing the surface.
 */
interface MirrorFrameSource {
    val frameSeq: State<Long>
    /** The newest decoded frame, or null before the first. */
    fun currentFrame(): ImageBitmap?
    fun onFrameDrawn(seq: Long) = Unit
}

/** Mirror: the host's screen at low resolution, where a finger on the image acts on the host. */
@Composable
fun MirrorView(
    btManager: BluetoothKeyboardManager,
    onClose: () -> Unit,
    launchMode: Int,
    onModeChange: (Int) -> Unit,
    sharedPrefs: SharedPreferences,
) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as BlukeApplication).mirrorController }
    val state by controller.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Mirror lives exactly while its surface is on screen: listening, the Wi-Fi lock, everything.
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    MirrorSurface(
        state = state,
        frames = controller.frames,
        onClose = onClose,
        onNextMode = {
            val enabled = sharedPrefs.enabledInputModes().map(InputMode::id)
            val index = enabled.indexOf(launchMode)
            onModeChange(enabled[(index + 1) % enabled.size])
        },
        onConfigureModes = { context.startActivity(Intent(context, QuickCycleActivity::class.java)) },
        onTouchModeChange = controller::setTouchMode,
        onForgetCompanion = controller::forgetCompanion,
        onNoticeShown = controller::clearNotice,
        onTouch = { frame ->
            // A finger landing wakes the Bluetooth link too, so a keystroke after Mirror play isn't late.
            if (frame.points.any { it.down && !it.wasDown }) btManager.holdLinkAwake()
            controller.onTouch(frame)
        },
        onImagePlaced = { controller.imageBoundsInWindow = it },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MirrorSurface(
    state: MirrorUiState,
    frames: MirrorFrameSource,
    onClose: () -> Unit,
    onNextMode: () -> Unit,
    onConfigureModes: () -> Unit,
    onTouchModeChange: (MirrorTouchMode) -> Unit,
    onForgetCompanion: () -> Unit,
    onNoticeShown: () -> Unit,
    onTouch: (MirrorTouchFrame) -> Unit,
    modifier: Modifier = Modifier,
    onImagePlaced: (FloatArray) -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("mirror_view_root"),
    ) {
        MirrorRail(
            state = state,
            onClose = onClose,
            onNextMode = onNextMode,
            onConfigureModes = onConfigureModes,
            onTouchModeChange = onTouchModeChange,
            onForgetCompanion = onForgetCompanion,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.End)),
        ) {
            val streaming = state.phase is MirrorPhase.Connected
            MirrorImage(frames = frames, live = streaming, onTouch = onTouch, onImagePlaced = onImagePlaced)
            MirrorOverlay(state = state)
            MirrorNoticePill(notice = state.notice, onShown = onNoticeShown)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MirrorRail(
    state: MirrorUiState,
    onClose: () -> Unit,
    onNextMode: () -> Unit,
    onConfigureModes: () -> Unit,
    onTouchModeChange: (MirrorTouchMode) -> Unit,
    onForgetCompanion: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxHeight().width(88.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.systemBars)
                        .only(androidx.compose.foundation.layout.WindowInsetsSides.Start + androidx.compose.foundation.layout.WindowInsetsSides.Vertical),
                )
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("exit_mirror_btn")) {
                Icon(Icons.Default.Close, contentDescription = "Close Mirror")
            }
            RailButton(
                icon = Icons.Filled.DesktopWindows,
                label = "Mirror",
                selected = false,
                modifier = Modifier
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = "Next input mode",
                        onLongClickLabel = "Choose input modes",
                        onClick = onNextMode,
                        onLongClick = onConfigureModes,
                    )
                    .testTag("mirror_mode_cycle_btn"),
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                modifier = Modifier.selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TouchModeOption(
                    mode = MirrorTouchMode.DIRECT,
                    icon = Icons.Filled.TouchApp,
                    label = "Direct",
                    description = "Direct: touch presses, drag drags",
                    selected = state.touchMode == MirrorTouchMode.DIRECT,
                    onSelect = onTouchModeChange,
                )
                TouchModeOption(
                    mode = MirrorTouchMode.HOVER,
                    icon = Icons.Filled.Mouse,
                    label = "Hover",
                    description = "Hover: touch moves the pointer, tap clicks",
                    selected = state.touchMode == MirrorTouchMode.HOVER,
                    onSelect = onTouchModeChange,
                )
            }
            Spacer(Modifier.weight(1f))
            LinkReadout(state)
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("mirror_menu_btn")) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More Mirror options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val paired = state.pairedName
                    DropdownMenuItem(
                        text = { Text(if (paired != null) "Forget $paired" else "No computer paired") },
                        enabled = paired != null,
                        onClick = {
                            menuOpen = false
                            onForgetCompanion()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .then(modifier)
            .sizeIn(minWidth = 64.dp, minHeight = 56.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(container)
                .size(width = 56.dp, height = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TouchModeOption(
    mode: MirrorTouchMode,
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onSelect: (MirrorTouchMode) -> Unit,
) {
    RailButton(
        icon = icon,
        label = label,
        selected = selected,
        modifier = Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(mode) })
            .semantics { contentDescription = description }
            .testTag("mirror_mode_${mode.name.lowercase()}"),
    )
}

/** Frames per second and link latency, the two numbers that say whether the picture is live. */
@Composable
private fun LinkReadout(state: MirrorUiState) {
    val connected = state.phase is MirrorPhase.Connected
    val fps = if (connected) "${state.fps} fps" else "– fps"
    val latency = state.latencyMs?.takeIf { connected }?.let { "$it ms" } ?: "– ms"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = if (connected) "$fps, link latency $latency" else "Not streaming"
            }
            .testTag("mirror_link_readout"),
    ) {
        Text(fps, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(latency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The mirrored picture and the touch layer over it; both use the same [MirrorFit]. */
@Composable
private fun MirrorImage(
    frames: MirrorFrameSource,
    live: Boolean,
    onTouch: (MirrorTouchFrame) -> Unit,
    onImagePlaced: (FloatArray) -> Unit,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Frame size changes rarely (a new display), so reading it here recomposes only then.
    val frameSize = remember { mutableStateOf(IntSize.Zero) }
    val fit: MirrorFit? = frameSize.value.takeIf { it.width > 0 && surfaceSize.width > 0 }?.let {
        fitMirror(it.width, it.height, surfaceSize.width.toFloat(), surfaceSize.height.toFloat(), allowRotation = true)
    }
    val currentFit by rememberUpdatedState(fit)
    val currentLive by rememberUpdatedState(live)
    val currentOnTouch by rememberUpdatedState(onTouch)
    LaunchedEffect(fit, origin) {
        fit?.let { onImagePlaced(floatArrayOf(origin.x + it.left, origin.y + it.top, origin.x + it.left + it.width, origin.y + it.top + it.height)) }
    }
    val dim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { surfaceSize = it }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val seq = frames.frameSeq.value
                val image = frames.currentFrame() ?: return@drawBehind
                if (frameSize.value.width != image.width || frameSize.value.height != image.height) {
                    frameSize.value = IntSize(image.width, image.height)
                }
                val drawFit = fitMirror(image.width, image.height, size.width, size.height, allowRotation = true)
                val boxCenter = Offset(drawFit.left + drawFit.width / 2f, drawFit.top + drawFit.height / 2f)
                val imageW = if (drawFit.rotation == MirrorRotation.CLOCKWISE) drawFit.height else drawFit.width
                val imageH = if (drawFit.rotation == MirrorRotation.CLOCKWISE) drawFit.width else drawFit.height
                translate(boxCenter.x, boxCenter.y) {
                    rotate(if (drawFit.rotation == MirrorRotation.CLOCKWISE) 90f else 0f, pivot = Offset.Zero) {
                        drawImage(
                            image = image,
                            dstOffset = IntOffset((-imageW / 2f).toInt(), (-imageH / 2f).toInt()),
                            dstSize = IntSize(imageW.toInt(), imageH.toInt()),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
                if (!currentLive) drawRect(dim)
                frames.onFrameDrawn(seq)
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val fitNow = currentFit
                        if (fitNow != null && currentLive) {
                            currentOnTouch(
                                MirrorTouchFrame(
                                    points = event.changes.map {
                                        MirrorTouchPoint(it.id.value, it.position.x, it.position.y, it.pressed, it.previousPressed)
                                    },
                                    uptimeMillis = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis(),
                                    fit = fitNow,
                                ),
                            )
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .semantics { contentDescription = "Mirrored computer screen" }
            .testTag("mirror_image"),
    )
}

/** Everything the surface says when there's no live picture to touch. */
@Composable
private fun MirrorOverlay(state: MirrorUiState) {
    val phase = state.phase
    if (phase is MirrorPhase.Connected && phase.hasFrame) return
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .heightIn(max = maxHeight)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("mirror_status_card"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (phase) {
                    MirrorPhase.Off -> StatusHeader(icon = null, title = "Starting Mirror", busy = true)
                    MirrorPhase.NoWifi -> {
                        StatusHeader(Icons.Filled.WifiOff, "Connect to Wi-Fi")
                        Body("Mirror reaches your computer over the local network. Join the Wi-Fi your computer is on.")
                    }
                    is MirrorPhase.Waiting -> if (phase.pairedName == null) {
                        StatusHeader(Icons.Filled.DesktopWindows, "Pair a computer")
                        Body("On the computer, in a terminal, run:")
                        Command("bluke-mirror pair")
                        PhoneAddressHint(state)
                    } else {
                        StatusHeader(icon = null, title = "Waiting for ${phase.pairedName}", busy = true)
                        Body("Run bluke-mirror on ${phase.pairedName}. It finds this phone on the network.")
                        PhoneAddressHint(state)
                    }
                    is MirrorPhase.Pairing -> {
                        StatusHeader(icon = null, title = "Pair with ${phase.companionName}")
                        Body("Type this code into bluke-mirror:")
                        Text(
                            text = phase.code.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.displayMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Pairing code ${phase.code.toList().joinToString(" ")}" }
                                .testTag("mirror_pairing_code"),
                            textAlign = TextAlign.Center,
                        )
                        Body(
                            if (phase.attemptsLeft == 1) "Last try this session." else "${phase.attemptsLeft} tries left this session.",
                            muted = true,
                        )
                    }
                    MirrorPhase.PairingLocked -> {
                        StatusHeader(Icons.Filled.Lock, "Too many wrong codes")
                        Body("Pairing is closed for now. Close Mirror and open it again for a new code.")
                    }
                    is MirrorPhase.Connected -> {
                        StatusHeader(icon = null, title = "Connected to ${phase.companionName}", busy = true)
                        Body("Waiting for the first picture.", muted = true)
                    }
                    is MirrorPhase.Failed -> {
                        StatusHeader(Icons.Filled.ErrorOutline, "Mirror can't listen")
                        Body(phase.reason)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(icon: ImageVector?, title: String, busy: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            icon != null -> Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Command(text: String) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PhoneAddressHint(state: MirrorUiState) {
    val argument = state.phoneArgument ?: return
    Body("If it doesn't find this phone, give it the address:", muted = true)
    Command("--phone $argument")
}

/** A one-line confirmation ("Paired with …", "Wrong code"), shown for a few seconds. */
@Composable
private fun MirrorNoticePill(notice: MirrorNotice?, onShown: () -> Unit) {
    if (notice == null) return
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(notice) {
        delay(NOTICE_MILLIS)
        currentOnShown()
    }
    val text = when (notice) {
        is MirrorNotice.Paired -> "Paired with ${notice.companionName}"
        is MirrorNotice.WrongCode -> when (notice.attemptsLeft) {
            0 -> "Wrong code"
            1 -> "Wrong code. 1 try left."
            else -> "Wrong code. ${notice.attemptsLeft} tries left."
        }
        MirrorNotice.Forgotten -> "Computer forgotten"
    }
    Box(Modifier.fillMaxSize().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("mirror_notice"),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

private const val NOTICE_MILLIS = 3_000L
