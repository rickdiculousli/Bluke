package dev.arnv.bluke.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.KeyboardControlKey
import androidx.compose.material.icons.filled.KeyboardOptionKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** ⇧ as an outlined up arrow, the shape keyboards print; Material has no Shift key icon. */
private val ShiftKeyGlyph: ImageVector = ImageVector.Builder("ShiftKey", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 4f)
        lineTo(4f, 12.5f)
        lineTo(8.5f, 12.5f)
        lineTo(8.5f, 20f)
        lineTo(15.5f, 20f)
        lineTo(15.5f, 12.5f)
        lineTo(20f, 12.5f)
        close()
    }
}.build()

/**
 * The modifier glyphs a hotkey's label is written with (⌃⌥⇧⌘), each drawn as an icon in the
 * label's colour and size: the app's typeface has none of them, and a fallback font draws them
 * thin and off the baseline.
 */
internal val MODIFIER_GLYPH_ICONS: Map<Char, ImageVector> = mapOf(
    '⌃' to Icons.Filled.KeyboardControlKey,
    '⌥' to Icons.Filled.KeyboardOptionKey,
    '⇧' to ShiftKeyGlyph,
    '⌘' to Icons.Filled.KeyboardCommandKey,
)

/**
 * [label] with each modifier glyph as an inline icon; TalkBack and tests read the glyph itself.
 * When [breakable], a line may break after the leading glyphs ("⇧" over "Tab"), as a button face
 * needs at large text.
 */
internal fun hotkeyLabelText(label: String, breakable: Boolean = false): AnnotatedString = buildAnnotatedString {
    val glyphs = label.takeWhile { it in MODIFIER_GLYPH_ICONS }.length
    label.forEachIndexed { i, c ->
        if (c in MODIFIER_GLYPH_ICONS) appendInlineContent(c.toString(), c.toString()) else append(c)
        if (breakable && i == glyphs - 1 && glyphs < label.length) append(ZERO_WIDTH_SPACE)
    }
}

private const val ZERO_WIDTH_SPACE = '\u200B'


/** The room each glyph icon takes in a line: a square as tall as the type. */
private val GLYPH_PLACEHOLDER = Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)

/** Where [text]'s glyph icons sit, for measuring it as [HotkeyText] draws it. */
internal fun glyphPlaceholders(text: AnnotatedString): List<AnnotatedString.Range<Placeholder>> =
    text.text.mapIndexedNotNull { i, c -> if (c in MODIFIER_GLYPH_ICONS) AnnotatedString.Range(GLYPH_PLACEHOLDER, i, i + 1) else null }

private val glyphContent: Map<String, InlineTextContent> = MODIFIER_GLYPH_ICONS.entries.associate { (c, icon) ->
    c.toString() to InlineTextContent(GLYPH_PLACEHOLDER) {
        Icon(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** A hotkey's label, or text naming one ("Add ⇧Tab"), with its modifier glyphs drawn as icons. */
@Composable
internal fun HotkeyText(
    label: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    breakable: Boolean = false,
) {
    if (style != null) {
        Text(
            hotkeyLabelText(label, breakable),
            modifier = modifier,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            inlineContent = glyphContent,
        )
    } else {
        Text(
            hotkeyLabelText(label, breakable),
            modifier = modifier,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            inlineContent = glyphContent,
        )
    }
}
