package dev.arnv.bluke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
fun KeyboardView(
    geometry: KeyboardGeometry,
    theme: KeyboardThemeDefinition,
    characterLayout: KeyboardCharacterLayout = KeyboardCharacterLayout.US_QWERTY,
    modifierStyle: KeyboardModifierStyle = KeyboardModifierStyle.PC,
    activePressedKeys: List<Int>,
    isConnected: Boolean = false,
    isCapsLockActive: Boolean,
    isNumLockActive: Boolean,
    isScrollLockActive: Boolean,
    isFnActive: Boolean = false,
    keySensitivity: Float = 6f,
    selectedStyleId: String? = null,
    onKeySelected: ((KeyLayoutInfo) -> Unit)? = null,
    onKeyPressChange: (Int, Boolean) -> Unit
) {
    // The pointer loop below outlives recompositions; read the latest callback and radius through these.
    val currentOnKeyPressChange by rememberUpdatedState(onKeyPressChange)
    val currentKeySensitivity by rememberUpdatedState(keySensitivity)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val keyboardRows = KeyboardLayouts.getLayout(geometry, characterLayout, modifierStyle)
            val allKeys = keyboardRows.flatten()

        val totalLayoutWidthInUnits = allKeys.maxOfOrNull { it.x + it.widthRatio } ?: 15.0f
        val totalLayoutHeightInUnits = allKeys.maxOfOrNull { it.y + it.heightRatio } ?: 5.0f

        val platePadding = 6.dp
        val outerBoxPadding = 6.dp

        // Adjust constraints perfectly to take up absolutely maximum available space without dead zones
        val usableWidthDp = maxWidth - (outerBoxPadding * 2) - (platePadding * 2)
        val usableHeightDp = maxHeight - (outerBoxPadding * 2) - (platePadding * 2)

        val widthBasedUnit = usableWidthDp / totalLayoutWidthInUnits
        val heightBasedUnit = usableHeightDp / totalLayoutHeightInUnits
        val baseUnitWidth = minOf(widthBasedUnit, heightBasedUnit)
        // A short geometry (HHKB's five rows) would leave bands of empty case: let its keys grow a
        // little taller than wide to use the height, never so much that they stop reading as keys.
        val baseUnitHeight = minOf(heightBasedUnit, baseUnitWidth * MAX_KEY_ASPECT)

        val plateWidth = baseUnitWidth * totalLayoutWidthInUnits
        val plateHeight = baseUnitHeight * totalLayoutHeightInUnits

        // Keyboard Case plate well holding absolutely-positioned keycaps (guarantees perfect grid alignments)
        Box(
            modifier = Modifier
                .width(plateWidth + (platePadding * 2))
                .height(plateHeight + (platePadding * 2))
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(10.dp),
                    spotColor = PlateInk.Shadow,
                    ambientColor = PlateInk.AmbientShadow,
                )
                .background(Color(theme.plateArgb), RoundedCornerShape(10.dp))
                .border(1.5.dp, PlateInk.Rim, RoundedCornerShape(10.dp))
                .padding(platePadding)
                .pointerInput(baseUnitWidth, baseUnitHeight, allKeys, onKeySelected) {
                        awaitPointerEventScope {
                            if (onKeySelected != null) {
                                var pointerWasDown = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChange = event.changes.firstOrNull { it.pressed }
                                    if (pressedChange != null && !pointerWasDown) {
                                        val pos = pressedChange.position
                                        allKeys.lastOrNull { key ->
                                            val keyLeft = baseUnitWidth.toPx() * key.x
                                            val keyRight = keyLeft + baseUnitWidth.toPx() * key.widthRatio
                                            val keyTop = baseUnitHeight.toPx() * key.y
                                            val keyBottom = keyTop + baseUnitHeight.toPx() * key.heightRatio
                                            pos.x in keyLeft..keyRight && pos.y in keyTop..keyBottom
                                        }?.let(onKeySelected)
                                    }
                                    pointerWasDown = pressedChange != null
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            // Map of PointerId to List of KeyCodes currently being pressed by that pointer
                            val currentPointers = mutableMapOf<Long, List<Int>>()
                            
                            while (true) {
                                val event = awaitPointerEvent()
                                val eventChanges = event.changes
                                
                                val newPointerStates = mutableMapOf<Long, List<Int>>()
                                
                                eventChanges.forEach { change ->
                                    if (change.pressed) {
                                        val pos = change.position
                                        val touchRadius = currentKeySensitivity.dp.toPx()
                                        
                                        // Find all keys that intersect the touch radius
                                        val intersectedKeys = allKeys.filter { key ->
                                            val keyLeft = baseUnitWidth.toPx() * key.x
                                            val keyRight = keyLeft + baseUnitWidth.toPx() * key.widthRatio
                                            val keyTop = baseUnitHeight.toPx() * key.y
                                            val keyBottom = keyTop + baseUnitHeight.toPx() * key.heightRatio
                                            
                                            // Closest point on the rectangle to the touch center
                                            val closestX = pos.x.coerceIn(keyLeft, keyRight)
                                            val closestY = pos.y.coerceIn(keyTop, keyBottom)
                                            
                                            val dx = pos.x - closestX
                                            val dy = pos.y - closestY
                                            
                                            (dx * dx + dy * dy) <= (touchRadius * touchRadius)
                                        }.map { it.keyCode }
                                        
                                        newPointerStates[change.id.value] = intersectedKeys
                                        change.consume()
                                    }
                                }
                                
                                // Synthesize differences to send onKeyPressChange
                                val oldAllPressed = currentPointers.values.flatten().toSet()
                                val newAllPressed = newPointerStates.values.flatten().toSet()
                                
                                val newlyPressed = newAllPressed - oldAllPressed
                                val newlyReleased = oldAllPressed - newAllPressed
                                
                                newlyReleased.forEach { keyCode ->
                                    currentOnKeyPressChange(keyCode, false)
                                }
                                newlyPressed.forEach { keyCode ->
                                    currentOnKeyPressChange(keyCode, true)
                                }
                                
                                currentPointers.clear()
                                currentPointers.putAll(newPointerStates)
                            }
                        }
                    }
            ) {
                allKeys.forEach { key ->
                    val keyX = key.x
                    val keyY = key.y

                    val keyWidth = baseUnitWidth * key.widthRatio
                    val keyHeight = baseUnitHeight * key.heightRatio

                    val keyStyle = theme.styleFor(key)
                    val keyColor = Color(keyStyle.backgroundArgb)
                    val legendColor = Color(keyStyle.legendArgb)


                    val isShiftActive = activePressedKeys.contains(0xE1) || activePressedKeys.contains(0xE5)
                    val isUppercase = isCapsLockActive xor isShiftActive
                    val isAlphabetic = key.legend.length == 1 && key.legend[0].isLetter()
                    val baseDisplayLegend = if (isAlphabetic) {
                        if (isUppercase) key.legend.uppercase() else key.legend.lowercase()
                    } else {
                        key.legend
                    }
                    val fnLegend = if (isFnActive) fnLegendForKey(key.keyCode) else null
                    val displayLegend = fnLegend ?: baseDisplayLegend
                    val displayShiftedLegend = if (fnLegend == null) key.shiftedLegend else ""

                    val isIndicatorActive = when (key.keyCode) {
                        0x39 -> isCapsLockActive
                        0x53 -> isNumLockActive
                        0x47 -> isScrollLockActive
                        else -> false
                    }

                    val finalGlowColor = if (isIndicatorActive) {
                        Color(theme.accentStyle.backgroundArgb).copy(alpha = 0.25f)
                    } else {
                        null
                    }

                    KeyCap(
                        legend = displayLegend,
                        shiftedLegend = displayShiftedLegend,
                        width = keyWidth,
                        height = keyHeight,
                        isPressedNow = { activePressedKeys.contains(key.keyCode) },
                        keyBgColor = keyColor,
                        legendColor = legendColor,
                        legendScale = keyStyle.legendScale,
                        isSelected = selectedStyleId == key.styleId,
                        baseUnitWidth = baseUnitWidth,
                        modifier = Modifier
                            .offset(
                                x = baseUnitWidth * keyX,
                                y = baseUnitHeight * keyY
                            ),
                        glowColor = finalGlowColor
                    )
                }
            }
        }
        }
    }

/** How much taller than wide a key may grow to fill a short geometry's height. */
private const val MAX_KEY_ASPECT = 1.15f

/** The plate's own light and shadow: part of the keyboard's material, drawn the same under every theme. */
private object PlateInk {
    val Shadow = Color.Black.copy(alpha = 0.5f)
    val AmbientShadow = Color.Black
    val Rim = Color.White.copy(alpha = 0.15f)
}
