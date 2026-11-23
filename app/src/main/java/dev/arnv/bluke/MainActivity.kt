package dev.arnv.bluke

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.sound.migrateSoundPreferences
import dev.arnv.bluke.data.migrateKeyboardCustomizationPreferences
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import dev.arnv.bluke.ui.HomeScreen
import dev.arnv.bluke.ui.PermissionStep
import dev.arnv.bluke.ui.permissionStep

private const val PERMISSION_REQUESTED_PREFERENCE = "bluetooth_permission_requested"

interface RemoteVolumeKeyHost {
    fun setRemoteVolumeKeyHandler(handler: ((keyCode: Int, isPressed: Boolean) -> Unit)?)
}

class MainActivity : ComponentActivity(), RemoteVolumeKeyHost {
    private lateinit var btManager: BluetoothKeyboardManager
    private lateinit var soundSynth: KeyboardSoundSynthesizer
    private var remoteVolumeKeyHandler: ((keyCode: Int, isPressed: Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (!::btManager.isInitialized || isDestroyed) return@registerForActivityResult
        // Notify Bluetooth service to re-check status after user interaction
        btManager.checkBluetoothCapabilities()
        ensureBluetoothSessionService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        migrateSoundPreferences(sharedPrefs)
        migrateKeyboardCustomizationPreferences(sharedPrefs)
        btManager = (application as BlukeApplication).bluetoothKeyboardManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btManager.serviceState.collect { ensureBluetoothSessionService() }
            }
        }
        val contentView = findViewById<View>(android.R.id.content)
        contentView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                private var terminalStateObserved = false

                override fun onPreDraw(): Boolean {
                    if (btManager.serviceState.value is BluetoothState.InitializingCapabilities) {
                        return false
                    }
                    if (!terminalStateObserved) {
                        terminalStateObserved = true
                        contentView.postInvalidateOnAnimation()
                        return false
                    }
                    if (contentView.viewTreeObserver.isAlive) {
                        contentView.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    return true
                }
            }
        )

        soundSynth = KeyboardSoundSynthesizer(applicationContext)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                HomeScreen(
                    btManager = btManager,
                    soundSynth = soundSynth,
                    onGrantPermission = ::requestBluetoothPermissions,
                )
            }
        }
    }

    /**
     * Home's Grant permission. Asks Android while it will still show the prompt; after a second denial it
     * won't, so this opens Bluke's app settings instead, and onResume's re-check picks up the change.
     */
    private fun requestBluetoothPermissions() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val denied = deniedBluetoothPermissions()
        when (permissionStep(
            askedBefore = prefs.getBoolean(PERMISSION_REQUESTED_PREFERENCE, false),
            deniedShowsRationale = denied.map(::shouldShowRequestPermissionRationale),
        )) {
            PermissionStep.REQUEST -> {
                prefs.edit().putBoolean(PERMISSION_REQUESTED_PREFERENCE, true).apply()
                permissionLauncher.launch(requestedPermissions())
            }
            PermissionStep.OPEN_APP_SETTINGS -> startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The permissions the manager checks, as `checkBluetoothCapabilities` does, that are still missing. */
    private fun deniedBluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN,
            ).filterNot(::granted)
        } else {
            val location = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (location.any(::granted)) emptyList() else location
        }

    private fun requestedPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    override fun onStart() {
        super.onStart()
        if (::btManager.isInitialized) {
            ensureBluetoothSessionService()
        }
    }

    override fun onStop() {
        if (::btManager.isInitialized) {
            btManager.releaseAllInputs()
            if (!btManager.isAppVisible() && btManager.connectedDevice.value == null && !btManager.hasPendingConnection.value) {
                stopService(android.content.Intent(this, BluetoothSessionService::class.java))
            }
        }
        super.onStop()
    }

    private fun ensureBluetoothSessionService() {
        if (!::btManager.isInitialized || !btManager.isAppVisible()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        when (btManager.serviceState.value) {
            BluetoothState.BluetoothOff, BluetoothState.PermissionRequired,
            BluetoothState.Unsupported, BluetoothState.ProfileNotSupported -> return
            else -> Unit
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this, android.content.Intent(this, BluetoothSessionService::class.java),
            )
        } catch (e: RuntimeException) {
            android.util.Log.w("BluetoothSession", "Could not start connection notification", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::btManager.isInitialized) {
            btManager.checkBluetoothCapabilities()
            ensureBluetoothSessionService()
        }
        if (::soundSynth.isInitialized) {
            soundSynth.reloadSelectedSoundPack()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = remoteVolumeKeyHandler
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (handler != null && isVolumeKey) {
            if (event.repeatCount == 0) {
                handler(keyCode, true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handler = remoteVolumeKeyHandler
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (handler != null && isVolumeKey) {
            handler(keyCode, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun setRemoteVolumeKeyHandler(
        handler: ((keyCode: Int, isPressed: Boolean) -> Unit)?,
    ) {
        remoteVolumeKeyHandler = handler
    }

    override fun onDestroy() {
        remoteVolumeKeyHandler = null
        super.onDestroy()
        if (::soundSynth.isInitialized) {
            soundSynth.release()
        }
    }
}
