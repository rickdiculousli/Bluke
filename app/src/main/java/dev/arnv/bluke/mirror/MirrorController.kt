package dev.arnv.bluke.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import dev.arnv.bluke.BuildConfig
import dev.arnv.bluke.ui.MirrorFrameSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Runs Mirror while its surface is on screen ([start] at `ON_START`, [stop] at `ON_STOP`): listens
 * on the Wi-Fi address, advertises over mDNS, pairs or admits the pinned companion, decodes its
 * frames and sends touches back. The decisions live in the pure units beside it
 * ([MirrorConnectionPolicy], [PhonePairing], [MirrorTouchPolicy], [LatestFrameSlot], [FramePool]).
 *
 * Threads: one accept thread per listening socket, one per connection (the reader), one writer per
 * session (so moves and button changes go out in order), one decoder, and the main thread for
 * touches and the 100 ms tick (heartbeat, ping, pairing expiry, rates).
 */
class MirrorController(context: Context) : MirrorFrameSource {
    private val appContext = context.applicationContext
    private val identity by lazy { MirrorIdentity(appContext) }
    private val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val lock = Any()

    private val _state = MutableStateFlow(MirrorUiState(touchMode = savedTouchMode()))
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()
    val frames: MirrorFrameSource get() = this

    // Everything below is guarded by [lock] unless marked @Volatile.
    private var running = false
    private var generation = 0
    private var sslContext: SSLContext? = null
    private var wifi: MirrorWifi? = null
    private var wifiAddress: InetAddress? = null
    private val servers = mutableListOf<ServerSocket>()
    private val openSockets = Collections.synchronizedSet(mutableSetOf<SSLSocket>())
    /** Open connections that haven't sent their first message yet, oldest first, with when each was accepted. */
    private val unrouted = LinkedHashMap<SSLSocket, Long>()
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var pairing = newPairing()
    // Written under the lock; the main thread's touches and tick read it without.
    @Volatile private var session: Session? = null
    private var listenError: String? = null
    private var displayName: String? = null
    private var hasFrame = false
    private var connectedAtMs = 0L

    /** Debug builds' rig turns this on so `adb forward` (which reaches the phone's loopback) works. */
    @Volatile var rigLoopback = false
        private set
    /** Where the image sits in the window, in pixels (left, top, right, bottom), for the rig's taps. */
    @Volatile var imageBoundsInWindow: FloatArray? = null
    @Volatile var loopbackPort: Int? = null
        private set

    private val touchPolicy = MirrorTouchPolicy(
        mode = _state.value.touchMode,
        tapSlopPx = 12f * appContext.resources.displayMetrics.density,
    )

    // Frames: the reader offers, the decoder takes, the render thread draws the pool's newest.
    private val slot = LatestFrameSlot<MirrorMessage.Frame>()
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "mirror-decode") }
    private var pool: FramePool<Bitmap>? = null
    private var poolSize = 0 to 0
    private val imageBitmaps = java.util.IdentityHashMap<Bitmap, ImageBitmap>()
    private val seqState = mutableLongStateOf(0L)
    override val frameSeq: State<Long> get() = seqState
    @Volatile private var currentImage: ImageBitmap? = null
    private val rates = MirrorRates()
    @Volatile private var lastDrawnSeq = -1L
    private val drawnCount = AtomicLong()
    private val decodedCount = AtomicLong()
    // The rig's pipeline numbers, in microseconds; debug builds only. MotionEvent time to onTouch
    // (the input system's share), onTouch to the Pointer's TLS flush (the app's), the two measured
    // together per Pointer (MotionEvent time to flush), Ping to Pong, and a frame's decode finishing
    // to its first draw.
    private val eventToTouchUs = RigSamples()
    private val touchToFlushUs = RigSamples()
    private val eventToFlushUs = RigSamples()
    private val pingRoundTripUs = RigSamples()
    // A decoded frame's seq and when its decode finished (System.nanoTime), for decoded -> drawn.
    @Volatile private var lastDecoded: Pair<Long, Long>? = null
    private val decodedToDrawnUs = RigSamples()

    override fun currentFrame(): ImageBitmap? = currentImage

    override fun onFrameDrawn(seq: Long) {
        if (seq == lastDrawnSeq) return
        lastDrawnSeq = seq
        lastDecoded?.takeIf { it.first == seq }?.let { decodedToDrawnUs.add((System.nanoTime() - it.second) / 1000) }
        drawnCount.incrementAndGet()
        rates.frameDrawn(SystemClock.uptimeMillis())
    }

    fun start() = synchronized(lock) {
        if (running) return
        running = true
        generation++
        pairing = newPairing()
        listenError = null
        try {
            sslContext = sslContext ?: identity.sslContext()
        } catch (e: Exception) {
            Log.e(TAG, "Mirror identity failed", e)
            listenError = "This phone couldn't make the secure key Mirror pairs with."
        }
        wifi = MirrorWifi(appContext) { address -> main.post { relisten(address) } }.also { it.start() }
        relistenLocked(null)
        main.post(tick)
        publishLocked()
    }

    fun stop() {
        val ending: Session?
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            ending = session
            session = null
            closeServersLocked()
            wifi?.stop()
            wifi = null
            wifiAddress = null
            synchronized(openSockets) { openSockets.toList() }.forEach { if (it !== ending?.socket) closeQuietly(it) }
            unrouted.clear()
            pairing = newPairing()
            displayName = null
            hasFrame = false
            slot.take()
            currentImage = null
            _state.update { it.copy(phase = MirrorPhase.Off, fps = 0, latencyMs = null, notice = null) }
        }
        main.removeCallbacks(tick)
        // Release any held button before the link goes, then close once the writer has sent it.
        touchPolicy.release().forEach { ending?.send(it) }
        ending?.closeAfterFlush()
    }

    fun setRigLoopback(on: Boolean) {
        rigLoopback = on
        synchronized(lock) {
            if (running) {
                relistenLocked(wifiAddress)
                publishLocked()
            }
        }
    }

    fun setTouchMode(mode: MirrorTouchMode) {
        touchPolicy.cancel().messages.forEach { session?.send(it) }
        touchPolicy.mode = mode
        prefs.edit { putString(TOUCH_MODE_PREFERENCE, mode.name) }
        _state.update { it.copy(touchMode = mode) }
    }

    fun forgetCompanion() {
        identity.forget()
        val ending = synchronized(lock) { session.also { session = null } }
        ending?.closeAfterFlush()
        synchronized(lock) { publishLocked(notice = MirrorNotice.Forgotten) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** The user turned down the pairing request on screen: its connection closes, no attempt used. */
    fun declinePairing() = synchronized(lock) {
        val name = (pairing.state as? PhonePairingState.ShowingCode)?.companionName ?: return
        pairing.decline()?.let { closeQuietly(it as SSLSocket) }
        publishLocked(notice = MirrorNotice.Declined(name))
    }

    /** Called on the main thread for every touch event on the image; returns what it did, for the ring and haptics. */
    fun onTouch(frame: MirrorTouchFrame): MirrorTouchUpdate {
        val touchedAtNs = System.nanoTime() // the uptime clock MotionEvent times are on
        eventToTouchUs.add((touchedAtNs - frame.eventNanos) / 1000)
        val update = touchPolicy.update(frame)
        session?.let { target ->
            update.messages.forEach { target.send(it, touchedAtNs = touchedAtNs, eventNs = frame.eventNanos) }
        }
        return update
    }

    /** What the rig reads through RigReceiver. */
    fun rigSnapshot(): Map<String, Any?> {
        val ui = _state.value
        val phase = ui.phase
        return mapOf(
            "phase" to phase.javaClass.simpleName,
            "code" to (phase as? MirrorPhase.Pairing)?.code,
            "paired" to ui.pairedName,
            "address" to ui.address,
            "port" to ui.port,
            "loopbackPort" to loopbackPort,
            "framesDecoded" to decodedCount.get(),
            "framesDrawn" to drawnCount.get(),
            "lastDrawnSeq" to lastDrawnSeq,
            "fps" to ui.fps,
            "latencyMs" to ui.latencyMs,
            "imageBounds" to imageBoundsInWindow?.toList(),
            "linkQuiet" to ui.linkQuiet,
            "eventToTouchUs" to eventToTouchUs.toList(),
            "touchToFlushUs" to touchToFlushUs.toList(),
            "eventToFlushUs" to eventToFlushUs.toList(),
            "pingRoundTripUs" to pingRoundTripUs.toList(),
            "decodedToDrawnUs" to decodedToDrawnUs.toList(),
        )
    }

    // --- listening ---

    private fun relisten(address: InetAddress?) = synchronized(lock) {
        if (!running) return
        relistenLocked(address)
        publishLocked()
    }

    private fun relistenLocked(address: InetAddress?) {
        closeServersLocked()
        wifiAddress = address
        val ssl = sslContext ?: return
        try {
            if (address != null) {
                val server = openServer(ssl, address)
                servers += server
                registerNsdLocked(server.localPort)
            }
            if (rigLoopback) {
                val server = openServer(ssl, InetAddress.getLoopbackAddress())
                servers += server
                loopbackPort = server.localPort
            }
            listenError = null
        } catch (e: IOException) {
            Log.e(TAG, "Mirror listen failed", e)
            listenError = "Another app may be using Mirror's network port."
        }
    }

    private fun openServer(ssl: SSLContext, address: InetAddress): SSLServerSocket {
        val factory = ssl.serverSocketFactory
        val server = try {
            factory.createServerSocket(MIRROR_PORT, BACKLOG, address)
        } catch (_: BindException) {
            factory.createServerSocket(0, BACKLOG, address)
        } as SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf("TLSv1.3")
        val gen = generation
        thread(name = "mirror-accept", isDaemon = true) { acceptLoop(server, gen) }
        return server
    }

    private fun closeServersLocked() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        loopbackPort = null
        nsdListener?.let { listener ->
            runCatching { appContext.getSystemService(NsdManager::class.java)?.unregisterService(listener) }
        }
        nsdListener = null
    }

    private fun registerNsdLocked(port: Int) {
        val nsd = appContext.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Bluke on ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("v", MIRROR_PROTOCOL_VERSION.toString())
            setAttribute("id", identity.fingerprint.toHex().take(16))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsdListener = listener }
    }

    private fun acceptLoop(server: ServerSocket, gen: Int) {
        while (true) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (_: IOException) {
                return
            }
            val admitted = synchronized(lock) {
                if (gen != generation) return@synchronized false
                val unroutedNow = unrouted.keys.toList()
                when (val admission = MirrorConnectionPolicy.admit(openSockets.size, unroutedNow, MAX_CONNECTIONS)) {
                    MirrorAdmission.Admit -> Unit
                    is MirrorAdmission.Evict -> {
                        unrouted -= admission.oldest
                        openSockets -= admission.oldest
                        closeQuietly(admission.oldest)
                    }
                    MirrorAdmission.Refuse -> return@synchronized false
                }
                openSockets += socket
                unrouted[socket] = SystemClock.uptimeMillis()
                true
            }
            if (!admitted) {
                closeQuietly(socket)
                continue
            }
            thread(name = "mirror-conn", isDaemon = true) {
                try {
                    handle(socket, gen)
                } catch (e: Exception) {
                    Log.i(TAG, "Mirror connection ended: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    synchronized(lock) { unrouted -= socket }
                    openSockets -= socket
                    closeQuietly(socket)
                }
            }
        }
    }

    // --- one connection ---

    private fun handle(socket: SSLSocket, gen: Int) {
        socket.tcpNoDelay = true
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.startHandshake()
        val peerFingerprint = certificateFingerprint(socket.session.peerCertificates.first())
        val input = BufferedInputStream(socket.inputStream, 64 * 1024)
        val output = BufferedOutputStream(socket.outputStream, 64 * 1024)
        // Nothing is read past the first message, and nothing written, before the route says who this is.
        val first = MirrorCodec.read(input)
        synchronized(lock) { unrouted -= socket }
        when (val route = MirrorConnectionPolicy.route(first, peerFingerprint, identity.pinnedCompanion()?.fingerprint)) {
            MirrorRoute.Pairing -> runPairing(socket, input, output, first, peerFingerprint)
            MirrorRoute.Session -> runSession(socket, input, output, gen)
            is MirrorRoute.Close -> route.reply?.let { writeNow(output, it) }
        }
    }

    private fun runPairing(
        socket: SSLSocket,
        input: BufferedInputStream,
        output: OutputStream,
        first: MirrorMessage,
        companionFingerprint: ByteArray,
    ) {
        socket.soTimeout = (PhonePairing.CODE_LIFETIME_MS + 10_000).toInt()
        var message = first
        try {
            while (true) {
                val step = synchronized(lock) {
                    pairing.onMessage(socket, message, identity.fingerprint, companionFingerprint)
                }
                step.pinned?.let { identity.pin(it) }
                if (step.pinned != null) {
                    // A newly pinned companion replaces the old one's live session at once, as Forget does.
                    val ending = synchronized(lock) { session.also { session = null } }
                    ending?.closeAfterFlush()
                }
                step.reply?.let { writeNow(output, it) }
                synchronized(lock) {
                    val result = step.reply as? MirrorMessage.PairResult
                    publishLocked(
                        notice = when {
                            step.pinned != null -> MirrorNotice.Paired(step.pinned.name)
                            result != null && result.status != MirrorMessage.PairResult.PAIRED &&
                                result.status != MirrorMessage.PairResult.UNAVAILABLE ->
                                MirrorNotice.WrongCode(pairing.attemptsLeft)
                            else -> null
                        },
                    )
                }
                if (step.close) return
                message = MirrorCodec.read(input)
            }
        } finally {
            synchronized(lock) {
                val wasShowing = pairing.state is PhonePairingState.ShowingCode
                val before = pairing.attemptsLeft
                pairing.onClosed(socket)
                val struck = wasShowing && pairing.attemptsLeft < before
                publishLocked(notice = if (struck) MirrorNotice.WrongCode(pairing.attemptsLeft) else null)
            }
        }
    }

    private fun runSession(socket: SSLSocket, input: BufferedInputStream, output: OutputStream, gen: Int) {
        val current = Session(socket, output)
        val replaced = synchronized(lock) {
            if (gen != generation) return
            val old = session
            session = current
            displayName = null
            hasFrame = false
            connectedAtMs = SystemClock.uptimeMillis()
            rates.reset(connectedAtMs)
            publishLocked()
            old
        }
        replaced?.closeAfterFlush()
        current.send(MirrorMessage.Welcome(MIRROR_PROTOCOL_VERSION, CODEC_JPEG, MIRROR_MAX_WIDTH, MIRROR_MAX_HEIGHT, FPS_CAP))
        // The companion answers a Ping every second, so 5 s of silence means the link is gone.
        socket.soTimeout = SESSION_SILENCE_MS
        try {
            while (true) {
                val message = MirrorCodec.read(input)
                rates.heard(SystemClock.uptimeMillis())
                when (MirrorConnectionPolicy.sessionAccepts(message)) {
                    SessionVerdict.CLOSE -> return
                    SessionVerdict.SKIP -> continue
                    SessionVerdict.HANDLE -> Unit
                }
                when (message) {
                    is MirrorMessage.Display -> synchronized(lock) {
                        displayName = message.name
                        publishLocked()
                    }
                    is MirrorMessage.Frame -> {
                        slot.offer(message)?.let { current.send(MirrorMessage.FrameAck(it.seq, MIRROR_FRAME_DROPPED)) }
                        try {
                            decoder.execute { decodeNext(current) }
                        } catch (_: RejectedExecutionException) {
                            return
                        }
                    }
                    is MirrorMessage.Pong -> {
                        val roundTrip = nowUs() - message.tUs
                        rates.roundTrip(roundTrip)
                        pingRoundTripUs.add(roundTrip)
                    }
                    else -> Unit
                }
            }
        } finally {
            current.closeAfterFlush()
            // The companion lets go of its own held button when the link drops; the fingers still
            // down send nothing more, even once a new session starts.
            main.post { touchPolicy.abandon() }
            synchronized(lock) {
                if (session === current) {
                    session = null
                    publishLocked()
                }
            }
        }
    }

    // --- frames ---

    private fun decodeNext(from: Session) {
        val frame = slot.take() ?: return
        if (frame.codec != CODEC_JPEG || !mirrorFrameSizeAllowed(frame.width, frame.height)) {
            from.send(MirrorMessage.FrameAck(frame.seq, MIRROR_FRAME_DROPPED))
            return
        }
        val started = SystemClock.elapsedRealtimeNanos()
        val framePool = poolFor(frame.width, frame.height)
        val target = framePool.acquire()
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = target
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size, options)
        } catch (_: IllegalArgumentException) {
            null // the JPEG's size didn't match its header: drop it
        }
        if (decoded !== target) {
            from.send(MirrorMessage.FrameAck(frame.seq, MIRROR_FRAME_DROPPED))
            return
        }
        framePool.publish(target)
        currentImage = imageBitmaps.getOrPut(target) { target.asImageBitmap() }
        lastDecoded = frame.seq to System.nanoTime()
        seqState.longValue = frame.seq
        decodedCount.incrementAndGet()
        val decodeUs = (SystemClock.elapsedRealtimeNanos() - started) / 1000
        from.send(MirrorMessage.FrameAck(frame.seq, decodeUs.coerceAtMost(MIRROR_FRAME_DROPPED - 1)))
        if (!hasFrame) synchronized(lock) {
            if (session === from) {
                hasFrame = true
                val name = runCatching { identity.pinnedCompanion()?.name }.getOrNull() ?: "computer"
                publishLocked(notice = MirrorNotice.Streaming(name))
            }
        }
    }

    private fun poolFor(width: Int, height: Int): FramePool<Bitmap> {
        val existing = pool
        if (existing != null && poolSize == (width to height)) return existing
        imageBitmaps.clear()
        poolSize = width to height
        return FramePool { Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565) }.also { pool = it }
    }

    // --- the tick: heartbeat, ping, pairing expiry, rates ---

    private var lastPingAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val current = session
            if (current != null) {
                touchPolicy.heartbeat(now, current.lastLiveSentAtMs)?.let { current.send(it) }
                if (now - lastPingAt >= PING_INTERVAL_MS) {
                    lastPingAt = now
                    current.send(MirrorMessage.Ping(nowUs()))
                }
            }
            synchronized(lock) {
                if (!running) return
                MirrorConnectionPolicy.pastFirstMessageDeadline(unrouted, now).forEach {
                    unrouted -= it
                    closeQuietly(it)
                }
                val before = pairing.attemptsLeft
                pairing.expire()?.let { closeQuietly(it as SSLSocket) }
                publishLocked(notice = if (pairing.attemptsLeft < before) MirrorNotice.WrongCode(pairing.attemptsLeft) else null)
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    // --- state ---

    private fun publishLocked(notice: MirrorNotice? = null) {
        val pinned = runCatching { identity.pinnedCompanion() }.getOrNull()
        val phase = mirrorPhase(
            running = running,
            listenError = listenError,
            session = session?.let {
                MirrorSessionFacts(pinned?.name ?: "computer", displayName, hasFrame, SystemClock.uptimeMillis() - connectedAtMs)
            },
            pairing = pairing.state,
            listening = wifiAddress != null || loopbackPort != null,
            pinnedName = pinned?.name,
        )
        val now = SystemClock.uptimeMillis()
        _state.update {
            it.copy(
                phase = phase,
                address = wifiAddress?.hostAddress,
                port = servers.firstOrNull()?.localPort ?: MIRROR_PORT,
                pairedName = pinned?.name,
                fps = if (session != null) rates.fps(now) else 0,
                latencyMs = if (session != null) rates.latencyMs()?.toInt() else null,
                linkQuiet = session != null && rates.linkQuiet(now),
                notice = notice ?: it.notice,
            )
        }
    }

    private fun newPairing() = PhonePairing(
        drawCode = { Spake2.randomCode(random) },
        drawScalar = { Spake2.randomScalar(random) },
        now = SystemClock::elapsedRealtime,
    )

    private fun savedTouchMode(): MirrorTouchMode =
        MirrorTouchMode.entries.firstOrNull { it.name == prefs.getString(TOUCH_MODE_PREFERENCE, null) }
            ?: MirrorTouchMode.DIRECT

    /** A verified session's writer: every message goes out in submit order, on one thread. */
    private inner class Session(val socket: SSLSocket, private val output: OutputStream) {
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "mirror-write") }
        /**
         * When a message the companion counts as liveness for a held button last went out. FrameAcks
         * come from the decoder, not the touch thread, so they don't count (or reset the heartbeat).
         */
        @Volatile var lastLiveSentAtMs = SystemClock.uptimeMillis()
            private set

        fun send(message: MirrorMessage, touchedAtNs: Long? = null, eventNs: Long? = null) {
            try {
                writer.execute {
                    try {
                        output.write(MirrorCodec.encode(message))
                        output.flush()
                        if (MirrorTouchPolicy.countsAsLiveness(message)) lastLiveSentAtMs = SystemClock.uptimeMillis()
                        if (touchedAtNs != null && message is MirrorMessage.Pointer) {
                            val flushedNs = System.nanoTime()
                            touchToFlushUs.add((flushedNs - touchedAtNs) / 1000)
                            eventNs?.let { eventToFlushUs.add((flushedNs - it) / 1000) }
                        }
                    } catch (_: IOException) {
                        closeQuietly(socket)
                    }
                }
            } catch (_: RejectedExecutionException) {
                // The session already closed.
            }
        }

        fun closeAfterFlush() {
            try {
                writer.execute { closeQuietly(socket) }
            } catch (_: RejectedExecutionException) {
                closeQuietly(socket)
            }
            writer.shutdown()
        }
    }

    private fun writeNow(output: OutputStream, message: MirrorMessage) {
        output.write(MirrorCodec.encode(message))
        output.flush()
    }

    private fun closeQuietly(socket: SSLSocket) {
        runCatching { socket.close() }
    }

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

    private companion object {
        const val TAG = "BlukeMirror"
        const val SERVICE_TYPE = "_bluke-mirror._tcp"
        const val TOUCH_MODE_PREFERENCE = "mirror_touch_mode"
        const val BACKLOG = 4
        const val MAX_CONNECTIONS = 4
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val SESSION_SILENCE_MS = 5_000
        const val PING_INTERVAL_MS = 1_000L
        const val TICK_MS = 100L
        const val CODEC_JPEG: Int = 1
        const val FPS_CAP = 20
    }
}

/** The newest 512 of one rig timing, kept only in debug builds (release builds never record). */
private class RigSamples {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun add(us: Long) {
        if (!BuildConfig.DEBUG) return
        samples.addLast(us)
        while (samples.size > 512) samples.removeFirst()
    }

    @Synchronized
    fun toList(): List<Long> = samples.toList()
}
