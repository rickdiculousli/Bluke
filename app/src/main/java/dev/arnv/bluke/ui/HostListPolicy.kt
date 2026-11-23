package dev.arnv.bluke.ui

import android.bluetooth.BluetoothClass

/** What kind of device a host looks like, from its name and Bluetooth class. */
enum class DeviceType(val displayName: String, val isSupported: Boolean) {
    COMPUTER("Computer", true),
    PHONE("Phone or tablet", true),
    TV("TV or streamer", true),
    AUDIO("Audio device", false),
    MOUSE("Input accessory", false),
    GAMEPAD("Game controller", false),
    WATCH("Watch or wearable", false),
    DEFAULT("Bluetooth device", true),
}

/** A host as the home screen shows it: plain data, so policies and screenshots need no BluetoothDevice. */
data class HostRef(
    val address: String,
    val name: String?,
    val bonded: Boolean,
    val hidCapable: Boolean = false,
    val type: DeviceType = DeviceType.DEFAULT,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

/**
 * The one meaning of "worth offering as a host": already paired, advertising HID, or a class that
 * takes a keyboard. Both the Nearby filter and each row's enabled state read it.
 */
fun isLikelyHost(host: HostRef): Boolean = host.bonded || host.hidCapable || host.type.isSupported

/** Paired hosts are never filtered: a host you paired is always listed. */
fun pairedHosts(bonded: List<HostRef>): List<HostRef> = bonded.distinctBy { it.address }

/** Found hosts that aren't paired yet; the hide-unnamed and hide-unlikely settings apply here only. */
fun nearbyHosts(
    scanned: List<HostRef>,
    bondedAddresses: Set<String>,
    hideUnnamed: Boolean,
    hideUnlikely: Boolean,
): List<HostRef> = scanned.distinctBy { it.address }.filter { host ->
    host.address !in bondedAddresses &&
        (!hideUnnamed || !host.name.isNullOrBlank()) &&
        (!hideUnlikely || isLikelyHost(host))
}

private val gamepadWords = listOf(
    "controller", "gamepad", "xbox", "playstation", "ps4", "ps5", "switch", "joycon", "nintendo", "wireless controller",
)

/** Classifies a device by its name, then its Bluetooth major and full class. */
fun classifyHost(name: String?, majorClass: Int?, deviceClass: Int?): DeviceType {
    val n = name?.lowercase() ?: ""
    fun any(vararg words: String) = words.any(n::contains)

    if (any("mouse", "atk", "g305", "trackpad", "pointer", "mx master", "keyboard", "keycap", "stylus") ||
        majorClass == BluetoothClass.Device.Major.PERIPHERAL
    ) {
        return if (gamepadWords.any(n::contains)) DeviceType.GAMEPAD else DeviceType.MOUSE
    }
    if (gamepadWords.any(n::contains) || n.contains("game") || majorClass == BluetoothClass.Device.Major.TOY) {
        return DeviceType.GAMEPAD
    }
    val isTvClass = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER ||
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR ||
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX
    if (isTvClass || any(
            "tv", "television", "shield", "chromecast", "firestick", "roku", "tizen", "webos", "bravia", "mibox", "mi box",
        )
    ) return DeviceType.TV
    if (any("buds", "ear", "headphone", "headset", "audio", "speaker", "sound", "music", "cmf", "soundcore", "airpods") ||
        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    ) return DeviceType.AUDIO
    if (any("watch", "wear", "fitbit", "band", "garmin", "wearable") || majorClass == BluetoothClass.Device.Major.WEARABLE) {
        return DeviceType.WATCH
    }
    if (any("laptop", "pc", "computer", "desktop", "mac", "linux", "windows", "host") ||
        majorClass == BluetoothClass.Device.Major.COMPUTER
    ) return DeviceType.COMPUTER
    if (any("phone", "galaxy", "iphone", "pixel", "oneplus", "xiaomi", "redmi", "mobile", "tablet", "ipad") ||
        majorClass == BluetoothClass.Device.Major.PHONE
    ) return DeviceType.PHONE
    return DeviceType.DEFAULT
}
