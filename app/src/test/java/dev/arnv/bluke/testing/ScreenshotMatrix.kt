package dev.arnv.bluke.testing

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import org.robolectric.RuntimeEnvironment

/**
 * Captures one composable in light and dark, at each font scale, in one call.
 *
 * Call it from a Robolectric test (a class carrying the Robolectric runner annotation, so `fastTest`
 * skips it) with a Roborazzi graphics mode of NATIVE. It needs no compose rule: a rule allows one
 * `setContent` per test, so each variant runs in its own fresh compose environment and activity.
 *
 * ```
 * captureMatrix(name = "home", landscape = false) { HomeScreen(...) }
 * ```
 *
 * For `name = "home"` and the default scales it writes, in the app module:
 * `src/test/screenshots/home-light-fs1.0.png`, `home-dark-fs1.0.png`,
 * `home-light-fs1.3.png` and `home-dark-fs1.3.png`; `landscape = true` adds `-land` before `.png`.
 * A scale is written with one decimal (`1.0`, `1.3`, `2.0`). Record baselines with
 * `./gradlew recordRoborazziDebug --tests '<your test class>'`; a plain test run compares nothing,
 * `verifyRoborazziDebug` compares.
 *
 * Each capture sets `theme_mode` in `app_prefs` (1 light, 2 dark, as `MyApplicationTheme` reads it) and
 * the matching night qualifier, sets `dynamic_color` to false so wallpaper can't change colors, and
 * scales fonts with `LocalDensity`. Every preference it touched is restored afterwards.
 *
 * The caller's `@Config` qualifiers set the screen size; [landscape] only adds `+land` or `+port`, so
 * give the class a size whose shape matches (e.g. `w800dp-h360dp-land-xhdpi` for landscape).
 * [content] is composed inside `MyApplicationTheme`; anything it needs (a manager, preferences) is
 * the caller's to build, and it must read its own state fresh in each composition because it is
 * composed once per variant, in a new activity.
 */
@OptIn(ExperimentalTestApi::class)
fun captureMatrix(
    name: String,
    landscape: Boolean = false,
    fontScales: List<Float> = listOf(1.0f, 1.3f),
    content: @Composable () -> Unit,
) {
    val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
    val saved = listOf(THEME_MODE, DYNAMIC_COLOR).associateWith { prefs.all[it] }
    try {
        for (dark in listOf(false, true)) {
            for (scale in fontScales) {
                prefs.edit()
                    .putInt(THEME_MODE, if (dark) 2 else 1)
                    .putBoolean(DYNAMIC_COLOR, false)
                    .commit()
                RuntimeEnvironment.setQualifiers(
                    // Qualifier order matters to the parser: orientation comes before night mode.
                    "+${if (landscape) "land" else "port"}-${if (dark) "night" else "notnight"}",
                )
                runAndroidComposeUiTest<ComponentActivity> {
                    setContent {
                        val base = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                            MyApplicationTheme(content = content)
                        }
                    }
                    waitForIdle()
                    onRoot().captureRoboImage(filePath = matrixFilePath(name, dark, scale, landscape))
                }
            }
        }
    } finally {
        prefs.edit().apply {
            saved.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }.commit()
    }
}

/** The path [captureMatrix] writes a variant to, relative to the `app` module. */
fun matrixFilePath(name: String, dark: Boolean, fontScale: Float, landscape: Boolean): String =
    "src/test/screenshots/$name-${if (dark) "dark" else "light"}-fs${"%.1f".format(java.util.Locale.ROOT, fontScale)}" +
        "${if (landscape) "-land" else ""}.png"

private const val APP_PREFS = "app_prefs"
private const val THEME_MODE = "theme_mode"
private const val DYNAMIC_COLOR = "dynamic_color"
