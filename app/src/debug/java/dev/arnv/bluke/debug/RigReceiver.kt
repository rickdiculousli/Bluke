package dev.arnv.bluke.debug

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import dev.arnv.bluke.BlukeApplication
import dev.arnv.bluke.BuildConfig
import dev.arnv.bluke.bluetooth.CURRENT_HID_DESCRIPTOR_REVISION
import dev.arnv.bluke.bluetooth.ConsumerControl
import dev.arnv.bluke.bluetooth.HID_DESCRIPTOR_REVISION_PREFERENCE
import dev.arnv.bluke.bluetooth.GamepadDpadOutputMode
import dev.arnv.bluke.bluetooth.numpadStroke
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.TouchpadScroller
import dev.arnv.bluke.ui.defaultInputModeKeys
import dev.arnv.bluke.ui.TouchpadScrollSettings
import org.json.JSONObject

/**
 * Lets tools/hid-rig drive the HID session over adb in debug builds:
 * `adb shell am broadcast -a dev.arnv.bluke.debug.RIG --es cmd <cmd> [extras]`.
 * The broadcast's result data is a JSON object: the state for `state`, `{"ok":...}` otherwise.
 */
class RigReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as BlukeApplication).bluetoothKeyboardManager
        val result = JSONObject()
        when (intent.getStringExtra("cmd")) {
            "state" -> {
                val host = manager.connectedDevice.value
                result.put("serviceState", manager.serviceState.value.javaClass.simpleName)
                result.put("lifecycle", manager.lifecycleState.value.toString())
                result.put("host", host?.address ?: JSONObject.NULL)
                result.put("status", manager.statusMessage.value)
                result.put("lastFailure", manager.lastConnectionFailure.value?.failure?.name ?: JSONObject.NULL)
                result.put("disconnecting", manager.isDisconnecting.value)
                result.put("capsLock", manager.capsLockState.value)
                result.put("numLock", manager.numLockState.value)
                result.put("scrollLock", manager.scrollLockState.value)
                result.put("hostReportsLockLights", manager.hostReportsLockLights.value)
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                result.put("address", adapter?.address ?: JSONObject.NULL)
                result.put("ok", true)
            }
            "connect" -> {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                val address = intent.getStringExtra("address")
                if (adapter == null || address == null) {
                    result.put("ok", false)
                } else {
                    manager.connectDevice(adapter.getRemoteDevice(address))
                    result.put("ok", true)
                }
            }
            "skip-onboarding" -> {
                // Marks an existing, up-to-date install, so home opens with no launch notice in the way.
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
                    putBoolean("has_seen_onboarding", true)
                    putInt("last_run_version_code", BuildConfig.VERSION_CODE)
                    putInt(HID_DESCRIPTOR_REVISION_PREFERENCE, CURRENT_HID_DESCRIPTOR_REVISION)
                }
                result.put("ok", true)
            }
            "disconnect" ->{ manager.disconnectDevice(); result.put("ok", true) }
            "key" -> {
                manager.sendKey(intent.getIntExtra("code", 0), intent.getBooleanExtra("press", true))
                result.put("ok", true)
            }
            "numpad" -> {
                val legend = intent.getStringExtra("legend") ?: ""
                if (numpadStroke(legend) != null) {
                    manager.sendNumpadKey(legend, intent.getBooleanExtra("press", true))
                    result.put("ok", true)
                } else {
                    result.put("ok", false).put("error", "no numpad key $legend")
                }
            }
            "mouse" -> {
                manager.sendMouseReport(
                    intent.getIntExtra("buttons", 0).toByte(),
                    intent.getIntExtra("x", 0).toByte(),
                    intent.getIntExtra("y", 0).toByte(),
                    intent.getIntExtra("wheel", 0).toByte(),
                    intent.getIntExtra("pan", 0).toByte(),
                )
                result.put("ok", true)
            }
            "wheel-stream" -> {
                // Timed scroll steps for measuring how a host turns them into scrolling.
                val ticks = intent.getIntExtra("ticks", 10)
                val interval = intent.getIntExtra("interval", 16).toLong()
                val wheel = intent.getIntExtra("wheel", 0).toByte()
                val pan = intent.getIntExtra("pan", 0).toByte()
                Thread {
                    repeat(ticks) {
                        manager.sendMouseReport(0, 0, 0, wheel, pan)
                        Thread.sleep(interval)
                    }
                }.start()
                result.put("ok", true)
            }
            "probe" -> {
                // Link latency against the pause before a report: after each gap, one wheel step, its send
                // time logged as wall-clock ms ("BlukeRig: sent <ms>") to pair with the host's arrival times.
                val gaps = (intent.getStringExtra("gaps") ?: "8,50,100,200,400,800,1600").split(",").map { it.trim().toLong() }
                val repeats = intent.getIntExtra("repeats", 3)
                Thread {
                    repeat(repeats) {
                        for (gap in gaps) {
                            Thread.sleep(gap)
                            android.util.Log.i("BlukeRig", "sent ${System.currentTimeMillis()} gap $gap")
                            manager.sendMouseReport(0, 0, 0, 1, 0)
                        }
                    }
                }.start()
                result.put("ok", true)
            }
            "wiggle" -> {
                // Two fingers swinging dy dp one way and back through the real scroll engine, ms per swing,
                // for swings half-swings; logs the steps sent ("BlukeRig: wiggle sent <n>").
                val dy = intent.getFloatExtra("dy", 90f)
                val millis = intent.getIntExtra("ms", 120).toLong()
                val swings = intent.getIntExtra("swings", 8)
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val scroller = TouchpadScroller(TouchpadScrollSettings.from(prefs).copy(momentum = false))
                Thread {
                    var time = android.os.SystemClock.uptimeMillis()
                    var sent = 0
                    manager.holdLinkAwake()
                    Thread.sleep(150)
                    scroller.begin(500f, 500f, time)
                    val steps = (millis / 8).toInt().coerceAtLeast(1)
                    var y = 500f
                    repeat(swings) { swing ->
                        val direction = if (swing % 2 == 0) 1 else -1
                        for (i in 1..steps) {
                            Thread.sleep(8); time += 8
                            y += direction * dy / steps
                            scroller.move(500f, y, time)?.let {
                                sent += kotlin.math.abs(it.wheel)
                                manager.sendMouseReport(0, 0, 0, it.wheel.toByte(), it.pan.toByte())
                            }
                        }
                    }
                    scroller.liftAll(time)?.let { sent += kotlin.math.abs(it.wheel); manager.sendMouseReport(0, 0, 0, it.wheel.toByte(), it.pan.toByte()) }
                    android.util.Log.i("BlukeRig", "wiggle sent $sent")
                }.start()
                result.put("ok", true)
            }
            "flick" -> {
                // A scripted two-finger flick through the real scroll engine and the saved scroll settings:
                // fingers travel dy dp over ms, lift, and the engine plays out at its frame rate.
                val dy = intent.getFloatExtra("dy", 300f)
                val millis = intent.getIntExtra("ms", 100).toLong()
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val scroller = TouchpadScroller(TouchpadScrollSettings.from(prefs))
                Thread {
                    var time = android.os.SystemClock.uptimeMillis()
                    fun send(report: dev.arnv.bluke.ui.ScrollReport?) {
                        report?.let { manager.sendMouseReport(0, 0, 0, it.wheel.toByte(), it.pan.toByte()) }
                    }
                    fun wait() { Thread.sleep(8); time += 8 }
                    scroller.begin(500f, 500f, time)
                    val steps = (millis / 8).toInt().coerceAtLeast(1)
                    for (i in 1..steps) {
                        wait()
                        send(scroller.move(500f, 500f + dy * i / steps, time))
                    }
                    send(scroller.liftAll(time))
                    while (scroller.isActive) { wait(); send(scroller.tick(time)) }
                }.start()
                result.put("ok", true)
            }
            "gamepad" -> {
                manager.sendGamepadReport(
                    intent.getIntExtra("buttons", 0),
                    intent.getIntExtra("dpad", 0),
                    intent.getFloatExtra("lx", 0f),
                    intent.getFloatExtra("ly", 0f),
                    intent.getFloatExtra("rx", 0f),
                    intent.getFloatExtra("ry", 0f),
                )
                result.put("ok", true)
            }
            "consumer" -> {
                val name = intent.getStringExtra("control")
                val control = ConsumerControl.entries.firstOrNull { it.name == name }
                manager.sendConsumerControl(control)
                result.put("ok", name == null || name == "NONE" || control != null)
            }
            "mode" -> {
                val value = intent.getStringExtra("value")
                val mode = GamepadDpadOutputMode.entries.firstOrNull { it.preferenceValue == value }
                result.put("ok", mode != null && manager.changeGamepadMode(mode))
            }
            "release" -> { manager.releaseAllInputs(); result.put("ok", true) }
            "mirror" -> {
                // tools/mirror-rig: open Mirror without taps through Settings, reach it over adb forward,
                // and read what it shows (the pairing code, frames drawn, where the image sits).
                val mirror = (context.applicationContext as BlukeApplication).mirrorController
                when (intent.getStringExtra("action") ?: "state") {
                    "setup" -> context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
                        putStringSet("cycle_connection_modes", defaultInputModeKeys + InputMode.MIRROR.preferenceKey)
                        putString("mirror_touch_mode", intent.getStringExtra("touch") ?: "DIRECT")
                    }
                    "loopback" -> mirror.setRigLoopback(true)
                    "forget" -> mirror.forgetCompanion()
                    "touch-mode" -> mirror.setTouchMode(MirrorTouchMode.valueOf(intent.getStringExtra("touch") ?: "DIRECT"))
                    "key-down" -> mirror.leftButton(true)
                    "key-up" -> mirror.leftButton(false)
                    else -> Unit
                }
                mirror.rigSnapshot().forEach { (key, value) ->
                    result.put(key, when (value) {
                        null -> JSONObject.NULL
                        is List<*> -> org.json.JSONArray(value)
                        else -> value
                    })
                }
                result.put("ok", true)
            }
            else -> result.put("ok", false).put("error", "unknown cmd")
        }
        setResultData(result.toString())
    }
}
