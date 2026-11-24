package dev.arnv.bluke

import dev.arnv.bluke.testing.captureMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h5000dp-port-hdpi", sdk = [36])
class HelpScreenshotTest {
    @Test
    fun help() = captureMatrix(name = "help", fontScales = listOf(1.0f, 1.3f, 2.0f)) {
        HelpContent(onBack = {})
    }
}
