package dev.arnv.bluke.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every label on the multimedia remote fits its button at font scale 1.3, on the smallest landscape
 * phone and a common one, held flat and upright: nothing overflows, nothing pokes out of its button,
 * and no line breaks inside a word.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MultimediaFitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val prefs = context.getSharedPreferences("multimedia-fit", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @After
    fun closeManager() {
        manager.close()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun smallPhoneLandscape() = assertLabelsFit(MultimediaPosture.LANDSCAPE)

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun smallPhoneUpright() = assertLabelsFit(MultimediaPosture.PORTRAIT_HELD)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneLandscape() = assertLabelsFit(MultimediaPosture.LANDSCAPE)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneUpright() = assertLabelsFit(MultimediaPosture.PORTRAIT_HELD)

    private fun assertLabelsFit(posture: MultimediaPosture) = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, FONT_SCALE)) {
                SurfaceTheme {
                    MediaPresentationView(
                        btManager = manager,
                        chrome = previewChrome(InputMode.MEDIA_PRESENTATION),
                        sharedPrefs = prefs,
                        postureOverride = posture,
                    )
                }
            }
        }
        waitForIdle()

        val screen = onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val deck = onNodeWithTag("multimedia_deck", useUnmergedTree = true).fetchSemanticsNode()
        val problems = mutableListOf<String>()
        var labels = 0
        deck.descendants().forEach { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: return@forEach
            val layout = node.textLayout() ?: return@forEach
            val button = node.ancestors().firstOrNull { it.config.getOrNull(SemanticsProperties.Role) == Role.Button } ?: return@forEach
            labels++
            // The semantics layout is laid out at the full width the text was offered, so its width
            // overflow flag is meaningless for centred text: measure the drawn lines against the node instead.
            if (layout.multiParagraph.didExceedMaxLines || layout.multiParagraph.height > node.size.height + EPSILON) {
                problems += "\"$text\" is cut off at the bottom (${layout.lineCount} lines)"
            }
            if ((0 until layout.lineCount).any { layout.getLineRight(it) - layout.getLineLeft(it) > node.size.width + EPSILON }) {
                problems += "\"$text\" is cut off at the side"
            }
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = true)
                if (end < text.length && !text[end].isWhitespace() && !text[end - 1].isWhitespace()) {
                    problems += "\"$text\" breaks mid-word after \"${text.substring(0, end)}\""
                }
            }
            if (!button.boundsInRoot.contains(node.boundsInRoot)) problems += "\"$text\" spills out of its button"
            if (!screen.contains(button.boundsInRoot)) problems += "\"$text\"'s button runs off the screen"
            if (button.size.width < 48 * density.density || button.size.height < 48 * density.density) {
                problems += "\"$text\"'s button is under 48dp (${button.size})"
            }
        }
        assertTrue("found no button labels", labels >= 10)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun SemanticsNode.descendants(): Sequence<SemanticsNode> =
        sequence { children.forEach { yield(it); yieldAll(it.descendants()) } }

    private fun SemanticsNode.ancestors(): Sequence<SemanticsNode> = generateSequence(parent) { it.parent }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    private fun Rect.contains(other: Rect) =
        other.left >= left - EPSILON && other.top >= top - EPSILON && other.right <= right + EPSILON && other.bottom <= bottom + EPSILON

    private companion object {
        const val FONT_SCALE = 1.3f
        const val EPSILON = 0.5f
    }
}
