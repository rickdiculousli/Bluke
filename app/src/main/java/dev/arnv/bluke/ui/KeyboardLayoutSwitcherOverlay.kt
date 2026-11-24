package dev.arnv.bluke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The typing layout that Shift+Space is about to switch to, on a solid container so the keycaps
 * beneath don't show through its labels.
 */
@Composable
internal fun KeyboardLayoutSwitcherOverlay(
    visible: Boolean,
    selectedLayout: KeyboardCharacterLayout,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        Surface(
            color = colors.surfaceContainerHighest,
            contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 6.dp,
            modifier = Modifier
                .testTag("keyboard_layout_switcher")
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Typing layout", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyboardCharacterLayout.entries.forEach { layout ->
                        val selected = layout == selectedLayout
                        Text(
                            text = layout.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) colors.primaryContainer else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Text(
                    text = "Set the host's input source to ${selectedLayout.hostLayoutName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
