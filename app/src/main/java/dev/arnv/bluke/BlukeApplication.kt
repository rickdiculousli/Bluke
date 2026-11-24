package dev.arnv.bluke

import android.app.Application
import android.app.Activity
import android.os.Bundle
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.mirror.MirrorController
import dev.arnv.bluke.sound.migrateSoundCyclePreferences
import dev.arnv.bluke.ui.migrateLockLightPreferences
import dev.arnv.bluke.ui.migrateSurfacePreferences
import dev.arnv.bluke.utils.DeveloperLogManager

class BlukeApplication : Application() {
    private val bluetoothKeyboardManagerDelegate = lazy {
        BluetoothKeyboardManager(applicationContext)
    }
    val bluetoothKeyboardManager: BluetoothKeyboardManager by bluetoothKeyboardManagerDelegate

    /** Mirror's link to the bluke-mirror companion; it listens only while the Mirror surface is on screen. */
    val mirrorController: MirrorController by lazy { MirrorController(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        DeveloperLogManager.init(applicationContext)
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        migrateLockLightPreferences(appPrefs)
        migrateSurfacePreferences(appPrefs)
        migrateSoundCyclePreferences(appPrefs)
        // Visibility belongs to the whole app, not just MainActivity (settings are activities too).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (bluetoothKeyboardManagerDelegate.isInitialized()) bluetoothKeyboardManager.setAppInForeground(true)
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities == 0 && bluetoothKeyboardManagerDelegate.isInitialized()) {
                    bluetoothKeyboardManager.setAppInForeground(false)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onTerminate() {
        if (bluetoothKeyboardManagerDelegate.isInitialized()) {
            bluetoothKeyboardManager.close()
        }
        super.onTerminate()
    }
}
