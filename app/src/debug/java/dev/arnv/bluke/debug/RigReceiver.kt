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
import dev.arnv.bluke.ui.TouchpadScroller
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
                result.put("capsLock", manager.capsLockState.value)
                result.put("numLock", manager.numLockState.value)
                result.put("scrollLock", manager.scrollLockState.value)
                result.put("gamepadModeSwitch", manager.gamepadModeSwitchState.value.name)
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
                // The same writes as OnboardingActivity's finish, so MainActivity opens straight to the session.
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
                    putBoolean("has_seen_onboarding", true)
                    putBoolean("has_seen_help", true)
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
            "flick" -> {
                // A scripted two-finger flick through the real scroll engine and the saved scroll settings:
                // fingers travel dy px over ms, lift, and the engine plays out at its frame rate.
                val dy = intent.getFloatExtra("dy", 300f)
                val millis = intent.getIntExtra("ms", 100).toLong()
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val scroller = TouchpadScroller(TouchpadScrollSettings.from(prefs))
                Thread {
                    var time = android.os.SystemClock.uptimeMillis()
                    fun frame() {
                        scroller.tick(time)?.let { manager.sendMouseReport(0, 0, 0, it.wheel.toByte(), it.pan.toByte()) }
                        Thread.sleep(8)
                        time += 8
                    }
                    scroller.begin(500f, 500f, time)
                    val steps = (millis / 8).toInt().coerceAtLeast(1)
                    for (i in 1..steps) {
                        scroller.move(500f, 500f + dy * i / steps, time)
                        frame()
                    }
                    scroller.liftAll(time)
                    while (scroller.isActive) frame()
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
            else -> result.put("ok", false).put("error", "unknown cmd")
        }
        setResultData(result.toString())
    }
}
