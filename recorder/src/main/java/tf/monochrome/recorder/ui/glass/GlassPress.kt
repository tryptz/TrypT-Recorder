package tf.monochrome.recorder.ui.glass

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.collectLatest

/**
 * How hard a sheet of glass is being pressed, and where.
 *
 * The AGSL shader takes a bulge centre and an amount, so glass knows how to
 * deform. This carries the press so both halves of the effect can read it:
 * the shader swells toward the finger, and the pane gives under it. Kept as
 * one object rather than two loose values because the centre is meaningless
 * without the amount — a bulge at full strength in the middle of a pane nobody
 * touched is worse than no bulge at all.
 */
@Stable
internal class GlassPress(
    val interactions: MutableInteractionSource,
) {
    var boxSize by mutableStateOf(IntSize.Zero)
    var pressPoint by mutableStateOf<Offset?>(null)
    var held by mutableStateOf(false)

    /** The swell, 0 at rest and 1 held down. Animated, so it never jumps. */
    var amount by mutableFloatStateOf(0f)

    /**
     * Where the finger is, as a fraction of the pane. The shader wants it in
     * these units, and the centre of the pane is the honest answer before
     * anything has been touched.
     */
    val center: Offset
        get() {
            val point = pressPoint
            val size = boxSize
            if (point == null || size.width == 0 || size.height == 0) {
                return Offset(0.5f, 0.5f)
            }
            return Offset(
                (point.x / size.width).coerceIn(0f, 1f),
                (point.y / size.height).coerceIn(0f, 1f),
            )
        }
}

internal object GlassPressDefaults {
    /**
     * How far a pressed pane gives.
     *
     * A rigid scale of a whole card reads, on a sheet of glass, as a picture of
     * glass on a button rather than the glass itself moving. Here the scale is
     * the *quiet* half — the dome is what you actually see — so it only has to
     * stop the pane feeling rigid.
     */
    const val SQUEEZE = 0.965f

    /**
     * Dome width, as a fraction of the pane's longest side.
     *
     * The shader's own default is a sixth of the width, tuned to pick out one
     * icon from a row of them. A pane that is itself the button wants the
     * swell across the whole of it — at a sixth of a full-width bar the dome is
     * a dimple.
     */
    const val BULGE = 0.42f
}

/** Tryptify's press spring: a press is met, a release settles. */
private val PressSpring: AnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 900f)

/**
 * A [GlassPress] wired to its own interaction source.
 *
 * Springs in and eases out, deliberately asymmetric: a press should feel like it
 * is being met, and a release like it is settling.
 */
@Composable
internal fun rememberGlassPress(): GlassPress {
    val press = remember { GlassPress(MutableInteractionSource()) }

    LaunchedEffect(press) {
        press.interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    press.pressPoint = interaction.pressPosition
                    press.held = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> press.held = false
            }
        }
    }

    // The swell is animated straight into press.amount, every frame, by the
    // animation itself. An animateFloatAsState copied across in a SideEffect
    // only ran when this composable recomposed — which the animation never made
    // it do, since its value was read only inside the SideEffect. So
    // press.amount froze at whatever it was when the finger lifted, and the
    // pane stayed swollen. A faint slab hid it; the solid glass slab shows it
    // plainly.
    LaunchedEffect(press) {
        snapshotFlow { press.held }.collectLatest { held ->
            val target = if (held) 1f else 0f
            animate(press.amount, target, animationSpec = PressSpring) { value, _ -> press.amount = value }
        }
    }
    return press
}

/**
 * Make a sheet of glass squeeze when it is pressed.
 *
 * Two things happen and they are meant to be read as one. The pane gives a
 * little under the finger — [squeeze] is a scale, small on purpose, because
 * glass that visibly shrinks reads as a button with a picture of glass on it —
 * and, on panes drawn with the shader, the material swells toward wherever the
 * finger landed. Screens that cannot run the shader still get the give, so the
 * press is never silent.
 *
 * The scale is read inside [graphicsLayer]'s lambda, which runs at draw time, so
 * holding a pane down redraws it without recomposing anything.
 *
 * No ripple: the deformation *is* the feedback, and a Material ripple on top of
 * it is a second answer to a question already answered — and a rectangular one,
 * over a pane whose corners are round.
 */
internal fun Modifier.glassSqueeze(
    press: GlassPress,
    enabled: Boolean = true,
    squeeze: Float = GlassPressDefaults.SQUEEZE,
    onClick: () -> Unit,
): Modifier = this
    .onSizeChanged { press.boxSize = it }
    .graphicsLayer {
        val s = 1f - (1f - squeeze) * press.amount
        scaleX = s
        scaleY = s
    }
    .clickable(
        interactionSource = press.interactions,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )

/**
 * A pressable glass button of any shape/corner (Tryptify's PressableGlass).
 *
 * A sheet of glass that is itself a button: the same recipe as [GlassPanel]
 * ([GlassMaterial]) — the live lens or the haze frost of [hazeState], and a
 * *solid* slab the AGSL shader turns into a bevelled, refracting pane with a
 * lens rim as wide as [corner] — that swells where the finger lands and gives
 * a little under it. [corner] is the button's corner; [Dp.Infinity], the
 * default, is right for a pill or a disc. On devices without the shader the
 * give is still there and the pane is the plain surface of [glassBase].
 *
 * It used to lay its slab down at a tenth of the tint in Tryptify as insurance
 * against a device where the shader no-ops. The shader builds its bevel from
 * that slab's alpha, so every button that *did* get the shader came out a flat
 * grey smudge with no edge. Asking whether the shader will run replaced the
 * guess (see [GlassMaterial]).
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = Dp.Infinity,
    hazeState: HazeState? = LocalGlassBackdrop.current,
    glass: GlassSettings = LocalGlass.current,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val press = rememberGlassPress()
    val shape = remember(corner) { lensClipShape(corner) }
    Box(
        modifier = modifier
            .glassSqueeze(
                press = press,
                enabled = enabled,
                onClick = onClick,
            )
            .clip(shape)
            .glassBase(glass),
        contentAlignment = Alignment.Center,
    ) {
        GlassMaterial(hazeState = hazeState, glass = glass, corner = corner, press = press)
        GlassInkScope(glass) { content() }
    }
}
