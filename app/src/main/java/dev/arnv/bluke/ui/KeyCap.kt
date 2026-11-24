package dev.arnv.bluke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KeyCap(
    legend: String,
    shiftedLegend: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    isPressed: Boolean = false,
    keyBgColor: Color,
    legendColor: Color,
    legendScale: Float = 1f,
    isSelected: Boolean = false,
    baseUnitWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    glowColor: Color? = null,
    /** Read while drawing, so a press shows in the frame it lands without waiting for recomposition. */
    isPressedNow: () -> Boolean = { isPressed },
    /**
     * What an accessibility service reads and does on this key. When set it replaces the drawn
     * legends' semantics, so TalkBack meets one button rather than loose text.
     */
    keySemantics: (SemanticsPropertyReceiver.() -> Unit)? = null,
) {
    val keyBorderColor = keyBgColor.darker()
    
    // Proportional radii mapped directly from KBSim's 5px/3px on a 54px keysize
    val outerRadius = baseUnitWidth * 0.092f
    val innerRadius = baseUnitWidth * 0.055f
    
    // Inset padding mapped directly from KBSim's keysize/9 and keysize/18
    val insetX = baseUnitWidth / 9f
    val insetTop = baseUnitWidth / 18f
    val insetBottom = baseUnitWidth / 6f // keysize * 3 / 18

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .then(
                if (keySemantics != null) {
                    Modifier.clearAndSetSemantics {
                        testTag = "key_$legend"
                        keySemantics()
                    }
                } else {
                    Modifier.testTag("key_$legend")
                }
            )
            .padding(1.dp)
            .then(
                if (isSelected) Modifier.border(2.dp, KeyCapInk.Selection, RoundedCornerShape(outerRadius))
                else Modifier
            )
    ) {
        // Underglow if active
        if (glowColor != null && glowColor != Color.Transparent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .background(glowColor.copy(alpha = glowColor.alpha * 0.45f), RoundedCornerShape(outerRadius))
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(outerRadius), spotColor = glowColor)
            )
        }

        // 1. Key Border Base Bezel (KBSim .keyborder)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(keyBorderColor, RoundedCornerShape(outerRadius))
                .border(1.dp, KeyCapInk.BezelEdge, RoundedCornerShape(outerRadius))
        )

        // 2. Key Top Bevel (KBSim .keytop, inset and styled)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = insetX,
                    end = insetX,
                    top = insetTop,
                    bottom = insetBottom
                )
                .drawBehind {
                    drawRoundRect(
                        color = if (isPressedNow()) keyBorderColor else keyBgColor,
                        cornerRadius = CornerRadius(innerRadius.toPx()),
                    )
                }
                .border(1.dp, KeyCapInk.TopEdge, RoundedCornerShape(innerRadius)),
            contentAlignment = Alignment.Center
        ) {
            // Every word legend (Shift, Delete, Backspace, F1) shares one size, smaller than a letter;
            // a legend shrinks below it only when it would not fit its cap at the current font scale.
            val fontScale = LocalDensity.current.fontScale
            val topWidth = (width - insetX * 2 - 2.dp).value - (baseUnitWidth.value * 0.16f)
            val topHeight = (height - insetTop - insetBottom - 2.dp).value
            fun fit(text: String, desired: Float, heightShare: Float): Float {
                val byWidth = topWidth / (LEGEND_EM_WIDTH * text.length.coerceAtLeast(1) * fontScale)
                val byHeight = topHeight * heightShare / (LEGEND_LINE_HEIGHT * fontScale)
                return minOf(desired, byWidth, byHeight).coerceAtLeast(3f)
            }
            val letterSize = (baseUnitWidth.value * 0.24f).coerceIn(3f, 13f) * legendScale
            val desiredMain = if (legend.length > 1) letterSize * WORD_LEGEND_SCALE else letterSize
            val desiredShift = (baseUnitWidth.value * 0.17f).coerceIn(2.5f, 9f) * legendScale
            val stacked = shiftedLegend.isNotEmpty()
            val mainFontSize = fit(legend, desiredMain, if (stacked) 0.55f else 0.9f).sp
            val shiftFontSize = fit(shiftedLegend, desiredShift, 0.4f).sp

            if (shiftedLegend.isNotEmpty()) {
                // Stack legends inside a column so the secondary text sits exactly slightly above the primary with perfect alignment
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(y = (baseUnitWidth.value * 0.055f).coerceAtMost(3f).dp)
                        .padding(
                            start = (baseUnitWidth.value * 0.08f).dp,
                            end = (baseUnitWidth.value * 0.08f).dp,
                            top = 0.dp,
                            bottom = 0.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(
                        (baseUnitWidth.value * 0.03f).coerceAtMost(2f).dp,
                    ),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = shiftedLegend,
                        fontSize = shiftFontSize,
                        color = legendColor.copy(alpha = 0.82f),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Text(
                        text = legend,
                        fontSize = mainFontSize,
                        color = legendColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
            } else {
                // Alphabetical or single legend, centered
                Text(
                    text = legend,
                    fontSize = mainFontSize,
                    color = legendColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = androidx.compose.ui.text.TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier.padding(
                        (baseUnitWidth.value * 0.04f).coerceIn(0.5f, 2f).dp,
                    ),
                )
            }
        }
    }
}



// Extension function to darken mechanical key highlights on pressing

fun Color.darker(): Color {
    return Color(
        red = (this.red * 0.82f).coerceIn(0f, 1f),
        green = (this.green * 0.82f).coerceIn(0f, 1f),
        blue = (this.blue * 0.82f).coerceIn(0f, 1f),
        alpha = this.alpha
    )
}

/** A word legend's size against a letter's. */
private const val WORD_LEGEND_SCALE = 0.78f
/** A bold sans character's average advance, in ems: enough to tell whether a legend fits its cap. */
private const val LEGEND_EM_WIDTH = 0.56f
private const val LEGEND_LINE_HEIGHT = 1.2f

/** The keycap's own edges and the theme editor's selection ring, the same under every theme. */
private object KeyCapInk {
    val Selection = Color(0xFF00BCD4)
    val BezelEdge = Color.Black.copy(alpha = 0.85f)
    val TopEdge = Color.Black.copy(alpha = 0.15f)
}
