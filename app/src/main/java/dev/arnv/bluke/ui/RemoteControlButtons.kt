package dev.arnv.bluke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A remote button's container and the content drawn on it: always a role and its on-role. */
@Immutable
internal data class RemoteButtonColors(val container: Color, val content: Color)

/**
 * A button held like a hardware key: [onPressedChange] gets true on touch-down and false on release,
 * and [onPress] (the haptic) fires on touch-down. The pressed look comes straight from the pressed
 * state, in the frame the finger lands. [description] is what TalkBack reads; [text] and [caption]
 * are what the button shows, wrapping rather than truncating.
 */
@Composable
internal fun RemoteHoldButton(
    description: String,
    colors: RemoteButtonColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    icons: List<ImageVector> = emptyList(),
    iconSize: Dp = 24.dp,
    text: String? = null,
    caption: String? = null,
    onPress: () -> Unit = {},
    onPressedChange: (Boolean) -> Unit,
) {
    val currentPressHandler by rememberUpdatedState(onPressedChange)
    val currentPress by rememberUpdatedState(onPress)
    var isPressed by remember { mutableStateOf(false) }
    val container = if (isPressed) lerp(colors.container, colors.content, PRESSED_TINT) else colors.container
    Column(
        modifier = modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(container)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    currentPress()
                    currentPressHandler(true)
                    currentPressHandler(false)
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        currentPress()
                        currentPressHandler(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                            currentPressHandler(false)
                        }
                    },
                )
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                icons.forEach { Icon(it, contentDescription = null, tint = colors.content, modifier = Modifier.size(iconSize)) }
            }
        }
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = colors.content,
                textAlign = TextAlign.Center,
            )
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = colors.content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** How far a pressed button's container moves toward its content colour. */
private const val PRESSED_TINT = 0.2f
