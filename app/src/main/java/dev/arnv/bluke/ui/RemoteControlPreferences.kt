package dev.arnv.bluke.ui

const val HARDWARE_VOLUME_REMOTE_PREFERENCE = "media_hardware_volume_remote"
const val TOUCHPAD_MODIFIER_POSITION_PREFERENCE = "touchpad_modifier_position"

enum class TouchpadModifierPosition(val preferenceValue: String, val displayName: String) {
    OFF("off", "Modifiers off"),
    LEFT("left", "Modifiers left"),
    RIGHT("right", "Modifiers right"),
    ;

    companion object {
        fun fromPreference(value: String?): TouchpadModifierPosition =
            if (value == "both") LEFT
            else entries.firstOrNull { it.preferenceValue == value } ?: OFF
    }
}
