package dev.arnv.bluke.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Neutral tones shared by every accent: a near-graphite grey with a trace of Bluke Blue's hue
// (Material Color Utilities, hue 270, chroma 4; variant chroma 8). Tones are named by their HCT tone.
private val N0 = Color(0xFF000000)
private val N4 = Color(0xFF0D0E11)
private val N6 = Color(0xFF121316)
private val N10 = Color(0xFF1B1B1F)
private val N12 = Color(0xFF1F1F23)
private val N17 = Color(0xFF292A2D)
private val N20 = Color(0xFF303034)
private val N22 = Color(0xFF343438)
private val N24 = Color(0xFF39393C)
private val N87 = Color(0xFFDBD9DD)
private val N90 = Color(0xFFE4E2E6)
private val N92 = Color(0xFFE9E7EC)
private val N94 = Color(0xFFEFEDF1)
private val N95 = Color(0xFFF2F0F4)
private val N96 = Color(0xFFF5F3F7)
private val N98 = Color(0xFFFBF8FD)
private val N100 = Color(0xFFFFFFFF)
private val NV30 = Color(0xFF44464F)
private val NV50 = Color(0xFF757780)
private val NV60 = Color(0xFF8F9099)
private val NV80 = Color(0xFFC5C6D0)
private val NV90 = Color(0xFFE1E2EC)
// Tertiary: Bluke Blue's hue turned 60 degrees, chroma 24.
private val T10 = Color(0xFF2A122C)
private val T20 = Color(0xFF412742)
private val T30 = Color(0xFF593D59)
private val T40 = Color(0xFF735572)
private val T80 = Color(0xFFE0BBDD)
private val T90 = Color(0xFFFED7FA)

private val LightNeutrals = lightColorScheme(
    tertiary = T40, onTertiary = N100, tertiaryContainer = T90, onTertiaryContainer = T10,
    error = Color(0xFFB3261E), onError = N100, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = N98, onBackground = N10,
    surface = N98, onSurface = N10, surfaceVariant = NV90, onSurfaceVariant = NV30,
    surfaceDim = N87, surfaceBright = N98,
    surfaceContainerLowest = N100, surfaceContainerLow = N96, surfaceContainer = N94,
    surfaceContainerHigh = N92, surfaceContainerHighest = N90,
    outline = NV50, outlineVariant = NV80,
    inverseSurface = N20, inverseOnSurface = N95, scrim = N0,
)

private val DarkNeutrals = darkColorScheme(
    tertiary = T80, onTertiary = T20, tertiaryContainer = T30, onTertiaryContainer = T90,
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = N6, onBackground = N90,
    surface = N6, onSurface = N90, surfaceVariant = NV30, onSurfaceVariant = NV80,
    surfaceDim = N6, surfaceBright = N24,
    surfaceContainerLowest = N4, surfaceContainerLow = N10, surfaceContainer = N12,
    surfaceContainerHigh = N17, surfaceContainerHighest = N22,
    outline = NV60, outlineVariant = NV30,
    inverseSurface = N90, inverseOnSurface = N20, scrim = N0,
)

/** Pure black backgrounds for OLED screens, with containers stepped up from black so cards still separate. */
private fun ColorScheme.onBlack(): ColorScheme = copy(
    background = N0, surface = N0, surfaceDim = N0, surfaceBright = N17,
    surfaceContainerLowest = N0, surfaceContainerLow = N4, surfaceContainer = N6,
    surfaceContainerHigh = N10, surfaceContainerHighest = N17,
)

private fun ColorScheme.withTones(tones: ToneSet): ColorScheme = copy(
    primary = Color(tones.primary), onPrimary = Color(tones.onPrimary),
    primaryContainer = Color(tones.primaryContainer), onPrimaryContainer = Color(tones.onPrimaryContainer),
    secondary = Color(tones.secondary), onSecondary = Color(tones.onSecondary),
    secondaryContainer = Color(tones.secondaryContainer), onSecondaryContainer = Color(tones.onSecondaryContainer),
    inversePrimary = Color(tones.inversePrimary), surfaceTint = Color(tones.primary),
)

/** The tonal set for [accentIndex] under [style]; an index out of range falls back to Bluke Blue. */
fun accentTones(accentIndex: Int, style: PaletteStyle): AccentTones = when (style) {
    PaletteStyle.MONOCHROME -> MONOCHROME_TONES
    PaletteStyle.DEFAULT -> ACCENT_TONES.getOrElse(accentIndex) { ACCENT_TONES.first() }
}

/** The static (non-wallpaper) scheme: designed neutrals plus the accent's tonal set. */
fun staticColorScheme(
    accentIndex: Int,
    style: PaletteStyle,
    dark: Boolean,
    blackBackground: Boolean = false,
): ColorScheme {
    val tones = accentTones(accentIndex, style)
    return if (dark) {
        DarkNeutrals.withTones(tones.dark).let { if (blackBackground) it.onBlack() else it }
    } else {
        LightNeutrals.withTones(tones.light)
    }
}

/** The scheme for [settings]: wallpaper colours when on and available, the static scheme otherwise. */
fun blukeColorScheme(context: Context, settings: AppearanceSettings, dark: Boolean): ColorScheme {
    val scheme = if (settings.dynamicColor && dynamicColorAvailable()) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        return staticColorScheme(settings.accentIndex, settings.paletteStyle, dark, settings.blackBackground)
    }
    return if (dark && settings.blackBackground) scheme.onBlack() else scheme
}

/** The appearance settings, kept current as they change in `app_prefs`. */
@Composable
fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var settings by remember(prefs) { mutableStateOf(AppearanceSettings.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == null || key in AppearanceSettings.KEYS) settings = AppearanceSettings.read(changed)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

/**
 * Bluke's theme. [forceDark] draws the dark scheme whatever the theme mode says: the input surfaces
 * are hardware you hold, so they are always dark, in the user's accent.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = rememberAppearanceSettings()
    val dark = forceDark || settings.themeMode.isDark(darkTheme)
    val colorScheme = remember(settings, dark) { blukeColorScheme(context, settings, dark) }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
