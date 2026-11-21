package app.brokoli5191.khrechner.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The app's animation vocabulary on top of the expressive [MotionScheme].
 *
 * Anything that moves, grows or changes shape uses a spring that overshoots; colour and opacity
 * use the scheme's critically damped "effects" springs so they never flicker past their target.
 * With the system "remove animations" setting on, every spec is a [snap].
 */
@Immutable
class AppMotion(private val scheme: MotionScheme, val reduced: Boolean) {

    /** Playful, clearly visible overshoot: screen changes, things popping in, numbers rolling. */
    fun <T> bouncy(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 420f)

    /** Quick with a small bounce: shape morphs, press feedback, small layout shifts. */
    fun <T> snappy(): FiniteAnimationSpec<T> = if (reduced) snap() else scheme.fastSpatialSpec()

    /** Colour and alpha. */
    fun <T> effects(): FiniteAnimationSpec<T> = if (reduced) snap() else scheme.fastEffectsSpec()
}

@Composable
fun rememberAppMotion(): AppMotion {
    val scheme = MaterialTheme.motionScheme
    val reduced = LocalReduceMotion.current
    return remember(scheme, reduced) { AppMotion(scheme, reduced) }
}

/** Squeezes the element while it is pressed and lets it spring back with a bounce on release. */
fun Modifier.bounceOnPress(interactionSource: InteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = rememberAppMotion().snappy(),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * One-off entrance: the element rises into place, overshoots and settles. [index] staggers
 * siblings so a list arrives as a quick cascade rather than as one block.
 */
fun Modifier.popIn(index: Int = 0): Modifier = composed {
    val motion = rememberAppMotion()
    val progress = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceIn(0, 6) * 40L)
            progress.animateTo(1f, motion.bouncy())
        }
    }
    graphicsLayer {
        val value = progress.value
        alpha = value.coerceIn(0f, 1f)
        translationY = (1f - value) * 28.dp.toPx()
        scaleX = 0.92f + 0.08f * value
        scaleY = 0.92f + 0.08f * value
    }
}
