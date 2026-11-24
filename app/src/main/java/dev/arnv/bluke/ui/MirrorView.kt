package dev.arnv.bluke.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.BlukeApplication
import dev.arnv.bluke.ui.settings.SettingsSection
import dev.arnv.bluke.ui.settings.settingsIntent
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.mirror.MirrorFingerRouter
import dev.arnv.bluke.mirror.MirrorFit
import dev.arnv.bluke.mirror.MirrorNotice
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorRotation
import dev.arnv.bluke.mirror.MirrorTouchEvent
import dev.arnv.bluke.mirror.MirrorTouchFrame
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPoint
import dev.arnv.bluke.mirror.MirrorTouchUpdate
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.fitMirror
import dev.arnv.bluke.mirror.PairingPrompt
import dev.arnv.bluke.mirror.isLive
import dev.arnv.bluke.mirror.pairingPrompt
import dev.arnv.bluke.mirror.pairingRequest
import dev.arnv.bluke.mirror.pictureStale
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
    host: SurfaceHost,
) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as BlukeApplication).mirrorController }
    val state by controller.state.collectAsState()
    // Read again whenever the surface starts, so a change made in Settings applies on return.
    var hapticsEnabled by remember(sharedPrefs) { mutableStateOf(sharedPrefs.getBoolean("haptics_enabled", true)) }
    var typingLayout by remember(sharedPrefs) { mutableStateOf(sharedPrefs.typingLayout()) }
    var hotkeys by remember(sharedPrefs) { mutableStateOf(sharedPrefs.mirrorHotkeys()) }
    // Hotkeys go out as Bluetooth keyboard keys, to the Bluetooth host, never over the mirror link.
    val presser = remember(btManager) { MirrorHotkeyPresser(btManager::sendKey) }
    val hotkeysEnabled = host.status == SurfaceHostStatus.Connected
    LaunchedEffect(hotkeysEnabled) { if (!hotkeysEnabled) presser.releaseAll() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Mirror lives exactly while its surface is on screen: listening, the Wi-Fi lock, everything.
    DisposableEffect(lifecycleOwner, controller, sharedPrefs) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    hapticsEnabled = sharedPrefs.getBoolean("haptics_enabled", true)
                    typingLayout = sharedPrefs.typingLayout()
                    controller.start()
                }
                Lifecycle.Event.ON_STOP -> {
                    presser.releaseAll()
                    controller.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            presser.releaseAll()
            controller.stop()
        }
    }

    val forget = rememberPendingForget {
        controller.forgetCompanion()
        // The notice with the Undo already said Mirror forgot it; don't say it twice.
        controller.clearNotice()
    }
    // The request whose code the person asked to see over the picture.
    var shownPairingCode by remember { mutableStateOf<String?>(null) }

    MirrorSurface(
        state = state,
        frames = controller.frames,
        onClose = onClose,
        onNextMode = {
            val enabled = sharedPrefs.enabledInputModes().map(InputMode::id)
            val index = enabled.indexOf(launchMode)
            onModeChange(enabled[(index + 1) % enabled.size])
        },
        onConfigureModes = { context.startActivity(settingsIntent(context, SettingsSection.INPUT_MODES)) },
        onOpenWifiSettings = {
            // The panel joins a network without leaving Bluke; before Android 10, the full settings page.
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
            else Settings.ACTION_WIFI_SETTINGS
            context.startActivity(Intent(action))
        },
        onTouchModeChange = controller::setTouchMode,
        onForgetCompanion = forget::request,
        onNoticeShown = controller::clearNotice,
        onTouch = { frame ->
            // A finger landing wakes the Bluetooth link too, so a keystroke after Mirror play isn't late.
            if (frame.points.any { it.down && !it.wasDown }) btManager.holdLinkAwake()
            controller.onTouch(frame)
        },
        onDeclinePairing = controller::declinePairing,
        onImagePlaced = { controller.imageBoundsInWindow = it },
        hapticsEnabled = hapticsEnabled,
        forgetPending = forget.pending,
        onUndoForget = forget::undo,
        shownPairingCode = shownPairingCode,
        onShowPairingCode = { shownPairingCode = it },
        hotkeys = MirrorHotkeysUi(hotkeys, enabled = hotkeysEnabled, hostName = host.name),
        typingLayout = typingLayout,
        onKey = controller::leftButton,
        onHotkey = { hotkey, down -> if (down) presser.press(hotkey) else presser.lift(hotkey) },
        onHotkeysChange = {
            hotkeys = it
            sharedPrefs.saveMirrorHotkeys(it)
        },
    )
}

private fun SharedPreferences.typingLayout(): KeyboardCharacterLayout =
    KeyboardCharacterLayout.fromPreference(getString(KEYBOARD_CHARACTER_LAYOUT_PREFERENCE, null))

/** A Forget that waits out its notice's undo window before it happens. */
@Stable
class PendingForget internal constructor() {
    var pending by mutableStateOf(false)
        private set

    fun request() {
        pending = true
    }

    fun undo() {
        pending = false
    }

    internal fun settle() {
        pending = false
    }
}

/**
 * Forget, held back for the undo window: [forget] runs once the window passes without an Undo,
 * or at once if the surface leaves the screen inside the window.
 */
@Composable
fun rememberPendingForget(forget: () -> Unit): PendingForget {
    val pendingForget = remember { PendingForget() }
    val currentForget by rememberUpdatedState(forget)
    val undoWindowMillis = noticeMillis(withAction = true)
    LaunchedEffect(pendingForget.pending) {
        if (!pendingForget.pending) return@LaunchedEffect
        delay(undoWindowMillis)
        pendingForget.settle()
        currentForget()
    }
    DisposableEffect(pendingForget) {
        onDispose { if (pendingForget.pending) currentForget() }
    }
    return pendingForget
}

/**
 * The whole surface, drawn from [state] alone: the picture fills it, and the left-hand controls
 * float over its left edge. [hapticsEnabled] is the app's haptics preference; [forgetPending] shows
 * "Mirror forgot …" with an Undo while the forget waits out its window. [shownPairingCode] is the
 * pairing request whose code the person asked to see over a live picture. [onKey] is the
 * left-button key going down or up, [onHotkey] a hotkey button's, and [editingHotkeys] opens the
 * hotkey editor at once (for screenshots).
 */
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
    onTouch: (MirrorTouchFrame) -> MirrorTouchUpdate,
    modifier: Modifier = Modifier,
    onImagePlaced: (FloatArray) -> Unit = {},
    hapticsEnabled: Boolean = false,
    forgetPending: Boolean = false,
    onUndoForget: () -> Unit = {},
    onDeclinePairing: () -> Unit = {},
    onOpenWifiSettings: () -> Unit = {},
    shownPairingCode: String? = null,
    onShowPairingCode: (String) -> Unit = {},
    hotkeys: MirrorHotkeysUi = MirrorHotkeysUi(),
    typingLayout: KeyboardCharacterLayout = KeyboardCharacterLayout.US_QWERTY,
    onKey: (down: Boolean) -> MirrorTouchUpdate = { NOTHING_DONE },
    onHotkey: (MirrorHotkey, down: Boolean) -> Unit = { _, _ -> },
    onHotkeysChange: (List<MirrorHotkey>) -> Unit = {},
    editingHotkeys: Boolean = false,
) {
    val prompt = state.phase.pairingPrompt(shownPairingCode)
    val haptics = rememberMirrorHaptics(hapticsEnabled)
    LaunchedEffect(state.notice) {
        when (state.notice) {
            is MirrorNotice.WrongCode -> haptics.reject()
            is MirrorNotice.Paired -> haptics.confirm()
            else -> Unit
        }
    }
    // The surface's own hints (the key or a hotkey can't act yet) show in the notice's place.
    var hint by remember { mutableStateOf<MirrorNotice?>(null) }
    var editing by remember { mutableStateOf(editingHotkeys) }
    val bounds = remember { MirrorControlBounds() }
    val live = state.phase.isLive
    // While the editor is open, TalkBack reaches only the editor, as a finger does.
    val underEditor = if (editing) Modifier.clearAndSetSemantics {} else Modifier
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("mirror_view_root"),
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)).then(underEditor)) {
            MirrorImage(
                frames = frames,
                live = live,
                stale = state.pictureStale,
                haptics = haptics,
                bounds = bounds,
                onTouch = onTouch,
                onImagePlaced = onImagePlaced,
            )
        }
        // The notice stacks above the status card, so neither covers the other; the column has
        // no pointer input, so touches outside them reach the picture. It starts right of the controls.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(underEditor)
                .windowInsetsPadding(MirrorControlsInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.End)))
                .padding(start = MIRROR_CONTROLS_WIDTH + 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
                MirrorNoticePill(
                    notice = hint ?: state.notice,
                    forgetPending = forgetPending,
                    pairedName = state.pairedName,
                    onShown = { if (hint != null) hint = null else onNoticeShown() },
                    onUndoForget = onUndoForget,
                )
                QuietLinkChip(state)
                SlowLinkChip(state)
                PairingChip(
                    request = state.phase.pairingRequest.takeIf { prompt == PairingPrompt.CHIP },
                    onShowCode = onShowPairingCode,
                    onDecline = onDeclinePairing,
                )
                MirrorOverlay(
                    state = state,
                    prompt = prompt,
                    hasStaleFrame = frames.currentFrame() != null,
                    onDeclinePairing = onDeclinePairing,
                    onOpenWifiSettings = onOpenWifiSettings,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
        }
        MirrorControls(
            state = state,
            hotkeys = hotkeys,
            bounds = bounds,
            haptics = haptics,
            onClose = onClose,
            onNextMode = onNextMode,
            onConfigureModes = onConfigureModes,
            onTouchModeChange = onTouchModeChange,
            onKey = { down ->
                val update = onKey(down)
                when {
                    MirrorTouchEvent.PRESSED in update.events -> haptics.press()
                    MirrorTouchEvent.RELEASED in update.events -> haptics.release()
                    // No live link: the key reads disabled, and a press says so by feel.
                    down && !live -> haptics.reject()
                    // Pressed before the pointer has gone anywhere this session: nowhere to press.
                    down && !state.keyHeld -> hint = MirrorNotice.KeyNeedsPointer
                }
            },
            onHotkey = { hotkey, down ->
                if (down) haptics.press()
                onHotkey(hotkey, down)
            },
            onHotkeysUnavailable = {
                haptics.reject()
                hint = MirrorNotice.HotkeysNeedBluetooth(hotkeys.hostName)
            },
            onEditHotkeys = { editing = true },
            onForgetCompanion = onForgetCompanion,
            modifier = underEditor,
        )
        // The editor fades in over the picture and out the same way.
        AnimatedVisibility(visible = editing, enter = fadeIn(EffectsSpring), exit = fadeOut(EffectsSpring)) {
            MirrorHotkeyEditor(
                hotkeys = hotkeys.list,
                typingLayout = typingLayout,
                onChange = onHotkeysChange,
                onDone = { editing = false },
            )
        }
    }
}

/** What a key press that did nothing returns. */
private val NOTHING_DONE = MirrorTouchUpdate(emptyList(), emptyList(), emptyList(), held = false)

/** The surface's haptics, each tied to the event that causes it; silent when the app's preference is off. */
internal class MirrorHaptics(private val view: View, private val enabled: Boolean) {
    /** A press reaches the computer: Direct touch-down, Hover tap, the left-button key or a hotkey going down. */
    fun press() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** The left-button key let go of the computer's button. */
    fun release() = perform(HapticFeedbackConstants.KEYBOARD_RELEASE)

    /** A segmented choice changed (Direct/Hover) or the input mode moved on. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** Pairing succeeded. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** The computer sent a wrong pairing code. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS,
    )

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
internal fun rememberMirrorHaptics(enabled: Boolean): MirrorHaptics {
    val view = LocalView.current
    return remember(view, enabled) { MirrorHaptics(view, enabled) }
}

/** Where a finger is on the picture, and whether it holds the computer's button down. */
private data class TouchMark(val x: Float, val y: Float, val held: Boolean)

/**
 * The mirrored picture and the touch layer over it; both use the same [MirrorFit]. A ring marks
 * each finger the touch policy is acting on, in the same frame it lands, before the computer's next
 * picture can: filled while Direct holds the button, hollow in Hover and while two fingers scroll.
 * It's wider than a fingertip so it shows around a resting finger, and fades briefly after the lift
 * so a quick tap still shows. A [stale] picture (the link went quiet) dims lightly.
 *
 * Touches arrive as raw MotionEvents: a down asks Android for unbuffered dispatch, so moves reach
 * the computer as they happen instead of batched to the next frame, and a system cancel is told
 * apart from a lift. A MotionEvent carries every finger on the window, the thumb on a control over
 * the picture too, so each pointer is tagged by where it landed ([MirrorFingerRouter], against
 * [bounds]) and the touch policy never sees the thumb as a second finger.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MirrorImage(
    frames: MirrorFrameSource,
    live: Boolean,
    stale: Boolean,
    haptics: MirrorHaptics,
    bounds: MirrorControlBounds,
    onTouch: (MirrorTouchFrame) -> MirrorTouchUpdate,
    onImagePlaced: (FloatArray) -> Unit,
) {
    val view = LocalView.current
    // Which fingers belong to the overlay's controls, decided where each landed.
    val router = remember { MirrorFingerRouter() }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Frame size changes rarely (a new display), so reading it here recomposes only then.
    val frameSize = remember { mutableStateOf(IntSize.Zero) }
    val fit: MirrorFit? = frameSize.value.takeIf { it.width > 0 && surfaceSize.width > 0 }?.let {
        fitMirror(it.width, it.height, surfaceSize.width.toFloat(), surfaceSize.height.toFloat(), allowRotation = true)
    }
    val currentFit by rememberUpdatedState(fit)
    val currentLive by rememberUpdatedState(live)
    val currentHaptics by rememberUpdatedState(haptics)
    val currentOnTouch by rememberUpdatedState(onTouch)
    // Read only while drawing, so a moving finger redraws the image without recomposing it.
    val marks = remember { mutableStateOf(emptyList<TouchMark>()) }
    // The marks a lift left behind, fading; a new touch replaces them at once.
    val lifted = remember { mutableStateOf(emptyList<TouchMark>()) }
    val liftAlpha = remember { Animatable(0f) }
    val lifts = remember { mutableIntStateOf(0) }
    LaunchedEffect(lifts.intValue) {
        if (lifts.intValue == 0) return@LaunchedEffect
        liftAlpha.snapTo(1f)
        liftAlpha.animateTo(0f, FastEffectsSpring)
    }
    // The stale picture dims as the status card fades in over it, and clears as it fades out; a
    // live picture whose link went quiet dims lightly, since it may be frozen.
    val dimAlpha = animateFloatAsState(
        when {
            !live -> DIM_ALPHA
            stale -> STALE_DIM_ALPHA
            else -> 0f
        },
        EffectsSpring,
        label = "mirrorDim",
    )
    LaunchedEffect(fit, origin) {
        fit?.let { onImagePlaced(floatArrayOf(origin.x + it.left, origin.y + it.top, origin.x + it.left + it.width, origin.y + it.top + it.height)) }
    }
    val scrim = MaterialTheme.colorScheme.scrim
    val ring = MaterialTheme.colorScheme.onSurface
    val ringFill = ring.copy(alpha = 0.35f)
    val halo = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { surfaceSize = it }
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Asks Android to leave edge-started swipes to the picture instead of Back. Android honours
            // this only for gestures that begin at an edge, and only the bottom 200dp of each edge.
            .then(if (live) Modifier.systemGestureExclusion() else Modifier)
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
                dimAlpha.value.takeIf { it > 0f }?.let { drawRect(scrim.copy(alpha = it)) }
                if (currentLive) {
                    val radius = TOUCH_RING_RADIUS.toPx()
                    val (shown, alpha) = marks.value.takeIf { it.isNotEmpty() }?.let { it to 1f }
                        ?: (lifted.value to liftAlpha.value)
                    if (alpha > 0f) {
                        shown.forEach { mark ->
                            val center = Offset(mark.x, mark.y)
                            // The halo keeps the ring readable on a light picture as well as a dark one.
                            drawCircle(halo, radius, center, alpha = alpha, style = Stroke(4.dp.toPx()))
                            if (mark.held) drawCircle(ringFill, radius, center, alpha = alpha)
                            drawCircle(ring, radius, center, alpha = alpha, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                frames.onFrameDrawn(seq)
            }
            .pointerInteropFilter { event ->
                val action = event.actionMasked
                // Moves as they arrive, not batched to the next frame: the computer sees them sooner.
                if (action == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(event)
                // The fit of the frame on screen now, as the draw maps it: the drawn size state lags a display change by a draw.
                val fitNow = frames.currentFrame()
                    ?.takeIf { surfaceSize.width > 0 }
                    ?.let { fitMirror(it.width, it.height, surfaceSize.width.toFloat(), surfaceSize.height.toFloat(), allowRotation = true) }
                    ?: currentFit
                // Routed on every event, live or not, so a finger is owned from the moment it lands.
                val frame = router.route(event.toMirrorTouchFrame(fitNow ?: NO_FIT)) { x, y ->
                    bounds.hits(Offset(origin.x + x, origin.y + y))
                }
                if (fitNow == null || !currentLive) {
                    marks.value = emptyList()
                    return@pointerInteropFilter true
                }
                // What the computer gets, the ring and the haptics all come from the touch policy's one record.
                val update = currentOnTouch(frame.copy(fit = fitNow))
                update.events.forEach {
                    if (it == MirrorTouchEvent.PRESSED || it == MirrorTouchEvent.TAPPED) currentHaptics.press()
                }
                val next = update.rings.map { TouchMark(it.x, it.y, update.held) }
                if (next.isEmpty() && marks.value.isNotEmpty()) {
                    lifted.value = marks.value
                    lifts.intValue++
                }
                marks.value = next
                true
            }
            .semantics { contentDescription = "Mirrored computer screen" }
            .testTag("mirror_image"),
    )
}

/** Every pointer of [this] event, in the image layer's pixels; a lifting pointer reads as up. */
private fun MotionEvent.toMirrorTouchFrame(fit: MirrorFit): MirrorTouchFrame {
    val action = actionMasked
    val changing = actionIndex
    val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
    val landing = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
    val cancelled = action == MotionEvent.ACTION_CANCEL
    return MirrorTouchFrame(
        points = (0 until pointerCount).map { i ->
            MirrorTouchPoint(
                id = getPointerId(i).toLong(),
                x = getX(i),
                y = getY(i),
                down = !cancelled && !(lifting && i == changing),
                wasDown = !(landing && i == changing),
            )
        },
        uptimeMillis = eventTime,
        fit = fit,
        cancelled = cancelled,
        eventNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) eventTimeNanos else eventTime * 1_000_000,
    )
}

// Springs from M3's standard motion scheme: effects for fades, spatial for size. Compose scales
// them by the system animator scale and snaps them when animations are off.
internal val EffectsSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)
private val FastEffectsSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f)
internal val SpatialSizeSpring: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)

/** A fit for routing touches before any picture has been placed. */
private val NO_FIT = MirrorFit(0f, 0f, 0f, 0f, MirrorRotation.NONE)

private const val DIM_ALPHA = 0.6f
private const val STALE_DIM_ALPHA = 0.3f
private val TOUCH_RING_RADIUS = 35.dp
