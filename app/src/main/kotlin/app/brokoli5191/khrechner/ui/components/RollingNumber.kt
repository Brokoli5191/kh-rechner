package app.brokoli5191.khrechner.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.brokoli5191.khrechner.ui.theme.rememberAppMotion

/**
 * Text whose characters roll like an odometer when it changes. Each character has its own slot,
 * counted from the right so the unit and the last digits stay put; changed characters spring in
 * from below when the value went [up] and from above when it went down.
 */
@Composable
fun RollingNumber(
    text: String,
    up: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val motion = rememberAppMotion()
    if (motion.reduced) {
        Text(text, modifier, style = style, color = color, maxLines = 1)
        return
    }

    // Slots are never taken away, only emptied, so a character that disappears can animate out.
    val slotCount = remember { mutableIntStateOf(0) }
    val slots = maxOf(slotCount.intValue, text.length)
    SideEffect { slotCount.intValue = slots }

    Row(modifier, verticalAlignment = Alignment.Bottom) {
        for (fromEnd in slots downTo 1) {
            key(fromEnd) {
                val character = text.getOrNull(text.length - fromEnd)
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        val direction = if (up) 1 else -1
                        ContentTransform(
                            targetContentEnter = slideInVertically(motion.bouncy()) { direction * it } + fadeIn(motion.effects()),
                            initialContentExit = slideOutVertically(motion.snappy()) { -direction * it } + fadeOut(motion.effects()),
                            sizeTransform = SizeTransform(clip = false) { _, _ -> motion.snappy() },
                        )
                    },
                    label = "digit",
                ) { shown ->
                    if (shown != null) Text(shown.toString(), style = style, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
