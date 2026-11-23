package dev.arnv.bluke.ui.theme

import android.content.SharedPreferences
import android.os.Build

const val THEME_MODE_PREFERENCE = "theme_mode"
const val DYNAMIC_COLOR_PREFERENCE = "dynamic_color"
const val ACCENT_COLOR_PREFERENCE = "accent_color_index"
const val PALETTE_STYLE_PREFERENCE = "palette_style"
/** Pure black dark backgrounds, for OLED screens. The key predates the name. */
const val BLACK_BACKGROUND_PREFERENCE = "high_contrast_mode"

/** Whether the app follows the system's dark setting or forces one. Stored as its [preferenceValue]. */
enum class ThemeMode(val preferenceValue: Int, val label: String) {
    SYSTEM(0, "System"),
    LIGHT(1, "Off"),
    DARK(2, "On"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromPreference(value: Int): ThemeMode = entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

/**
 * How the static schemes are coloured. Only styles that render differently exist: every other value
 * Bluke once offered (Tonal Spot, Vibrant, Expressive, Rainbow, Fruit Salad, Fidelity, Content, Neutral)
 * drew the same scheme, so they read as [DEFAULT].
 */
enum class PaletteStyle(val preferenceValue: String, val label: String) {
    DEFAULT("Default", "Accent colour"),
    MONOCHROME("Monochrome", "Monochrome"),
    ;

    companion object {
        fun fromPreference(value: String?): PaletteStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}

/** Rewrites a stored palette style that no longer exists as [PaletteStyle.DEFAULT]. Safe to run twice. */
fun migratePaletteStyle(prefs: SharedPreferences) {
    val stored = prefs.getString(PALETTE_STYLE_PREFERENCE, null) ?: return
    val style = PaletteStyle.fromPreference(stored)
    if (style.preferenceValue != stored) {
        prefs.edit().putString(PALETTE_STYLE_PREFERENCE, style.preferenceValue).apply()
    }
}

/** Everything that decides the app's colours, as the user set it. */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = dynamicColorAvailable(),
    val accentIndex: Int = 0,
    val paletteStyle: PaletteStyle = PaletteStyle.DEFAULT,
    val blackBackground: Boolean = false,
) {
    companion object {
        fun read(prefs: SharedPreferences): AppearanceSettings = AppearanceSettings(
            themeMode = ThemeMode.fromPreference(prefs.getInt(THEME_MODE_PREFERENCE, ThemeMode.SYSTEM.preferenceValue)),
            dynamicColor = prefs.getBoolean(DYNAMIC_COLOR_PREFERENCE, dynamicColorAvailable()),
            accentIndex = prefs.getInt(ACCENT_COLOR_PREFERENCE, 0),
            paletteStyle = PaletteStyle.fromPreference(prefs.getString(PALETTE_STYLE_PREFERENCE, null)),
            blackBackground = prefs.getBoolean(BLACK_BACKGROUND_PREFERENCE, false),
        )

        /** The keys [read] depends on; a change to any of them changes the scheme. */
        val KEYS = setOf(
            THEME_MODE_PREFERENCE, DYNAMIC_COLOR_PREFERENCE, ACCENT_COLOR_PREFERENCE,
            PALETTE_STYLE_PREFERENCE, BLACK_BACKGROUND_PREFERENCE,
        )
    }
}

/** Wallpaper colours exist from Android 12. */
fun dynamicColorAvailable(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.S

/** One appearance control a settings screen can show. */
enum class AppearanceOption {
    THEME_MODE,
    DYNAMIC_COLOR,
    ACCENT,
    PALETTE_STYLE,
    BLACK_BACKGROUND,
}

/**
 * The appearance controls that change something in this state, in display order. Wallpaper colours
 * replace the accent and palette style, so those show only with it off; pure black applies only to a
 * dark scheme. A settings screen renders this list and owns no rule of its own.
 */
fun appearanceOptions(
    dynamicColor: Boolean,
    dark: Boolean,
    dynamicAvailable: Boolean = dynamicColorAvailable(),
): List<AppearanceOption> = buildList {
    add(AppearanceOption.THEME_MODE)
    if (dynamicAvailable) add(AppearanceOption.DYNAMIC_COLOR)
    val wallpaper = dynamicAvailable && dynamicColor
    if (!wallpaper) {
        add(AppearanceOption.ACCENT)
        add(AppearanceOption.PALETTE_STYLE)
    }
    if (dark) add(AppearanceOption.BLACK_BACKGROUND)
}
