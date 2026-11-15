package tf.monochrome.recorder.ui.glass

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** Tryptify's `MonoDimens.radiusLg`: the corner of its large panels. */
internal val GlassRadiusLg: Dp = 16.dp

/**
 * A floating pane of glass. No forced fillMaxWidth/padding/navigationBars
 * padding: the caller sizes and places it.
 *
 * There is more subtlety in here than its size suggests, and every bit of it
 * was learned the hard way in Tryptify:
 *
 * * **Two tiers, and the last one is real.** Shader glass on API 33+ (the live
 *   lens, or the haze frost, under the AGSL slab), and a Material 3 surface
 *   otherwise. That last branch cannot be a no-op modifier — it is the only
 *   thing standing between the pane and having no background at all.
 * * **Frost first.** The slab goes down to a thin body, and without a backdrop
 *   layer beneath it whatever is behind reads straight through and fights the
 *   pane's text.
 * * **A round shader canvas, not a rect.** The glass shader builds its bevel and
 *   rim from the gradient of what the canvas draws. A full-bleed rect gives it
 *   straight edges only, the parent clip then cuts the corners away, and each
 *   corner is left with no rim — four visible gaps in the outline.
 * * **Nothing gets through**, when [blockTouchesBelow] asks for it. Over a
 *   surface that takes taps anywhere, a tap on the pane's background would
 *   otherwise reach down to it. The backstop is the pane's *bottom sibling*,
 *   not a modifier on the pane itself: as an ancestor it swallowed drags before
 *   the content's scroll could claim them, and gestures that take several
 *   events to declare themselves — which is every scroll — were lost two times
 *   in three. Off by default, because it consumes drags as well as taps: a
 *   swipe that started on a pane inside a scrolling list could not scroll it.
 *
 * [hazeState] is the backdrop to frost and bend, a source this pane is a
 * SIBLING of. Null when the caller has none — the pane then takes the shader
 * glass alone instead of asking haze to blur a backdrop that was never fed,
 * which paints its base colour and reads as a solid container.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = GlassRadiusLg,
    hazeState: HazeState? = LocalGlassBackdrop.current,
    glass: GlassSettings = LocalGlass.current,
    blockTouchesBelow: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(corner) { lensClipShape(corner) }
    Box(
        modifier = modifier
            .clip(shape)
            .glassBase(glass),
    ) {
        // The backstop for taps the pane's own children didn't want, and the
        // lowest layer on purpose.
        //
        // An ancestor cannot swallow "what the children didn't want", because it
        // only learns what they wanted one event at a time: a tap resolves
        // within a single event and survived, but a *drag* has to cross touch
        // slop over several, and the ancestor consumed each one before the
        // scroll had accumulated enough of them to claim the gesture.
        //
        // As the bottom sibling it is only reached where nothing above it
        // handles the touch, which is exactly the case it is for — and being a
        // hit at all is what keeps the event inside this pane, so whatever is
        // underneath never sees it.
        if (blockTouchesBelow) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }

        GlassMaterial(hazeState = hazeState, glass = glass, corner = corner)
        GlassInkScope(glass) { content() }
    }
}

/**
 * A non-interactive pill of glass (e.g. a segmented-control track or a dock
 * background): [GlassPanel]'s material with fully round ends, from the same
 * settings, so a pill and a sheet on one screen cannot drift apart. The caller
 * sizes it; its corner is always half its short side.
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalGlassBackdrop.current,
    glass: GlassSettings = LocalGlass.current,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier,
        corner = Dp.Infinity,
        hazeState = hazeState,
        glass = glass,
        blockTouchesBelow = false,
        content = content,
    )
}

/**
 * Whether [glass] will be drawn by the shader here: on, and on Android 13 or
 * later.
 */
internal fun shaderGlassFor(glass: GlassSettings): Boolean =
    glass.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The base a glass pane is laid on: nothing under shader glass (its own
 * layers are the pane), and a Material 3 surface otherwise, which cannot be a
 * no-op — it is the only thing standing between the pane and no background at
 * all. There is no third, frosted tier: the app is liquid glass or Material 3.
 */
@Composable
internal fun Modifier.glassBase(
    glass: GlassSettings,
    fallbackMinAlpha: Float = FALLBACK_MIN_ALPHA,
): Modifier = then(
    if (shaderGlassFor(glass)) {
        Modifier
    } else {
        // Not a fully opaque slab when the material asks for see-through
        // glass, so body opacity governs this path too — floored so text stays
        // readable over whatever is behind.
        Modifier.background(
            MaterialTheme.colorScheme.surfaceContainerHigh
                .copy(alpha = glass.bodyOpacity.coerceIn(fallbackMinAlpha.coerceIn(0f, 1f), 1f)),
        )
    },
)

/** The fallback's least opacity: text stays readable over whatever is behind. */
internal const val FALLBACK_MIN_ALPHA = 0.55f

/**
 * The layers that make a pane glass, under its content: the backdrop pane (the
 * live lens, or the haze frost) and the shader slab.
 *
 * One recipe for [GlassPanel], [GlassPill] and [GlassButton], so a sheet and a
 * pill on the same screen are the same material from the same settings and
 * cannot drift apart. [corner] is the pane's corner radius: the lens rim is as
 * wide as it, and the slab is drawn with it. [press], when the pane is a
 * button, swells the slab under the finger.
 */
@Composable
internal fun BoxScope.GlassMaterial(
    hazeState: HazeState?,
    glass: GlassSettings,
    corner: Dp,
    press: GlassPress? = null,
) {
    if (!shaderGlassFor(glass)) return
    // Asked of this pane's own glass, not the screen's.
    val shaded = rememberLiquidGlassAvailable(glass)
    val tint = glassTint(glass.tintColor)
    // What the pane lies over: the page, unless the screen says otherwise.
    // The frost washes toward it.
    val frostBg = LocalGlassGround.current ?: MaterialTheme.colorScheme.background
    val isDark = frostBg.luminance() <= 0.5f
    // shaderGlassFor above has already asked for Android 13, which the lens
    // needs, so the version is not asked again here.
    val liveLens = LIVE_LENS_GLASS && liveLensCompiles && hazeState != null
    // Only with a real backdrop. Frosting an unfed state draws the frost's own
    // base colour as a flat pane, which is the slab this whole component
    // exists not to be.
    if (liveLens) {
        Box(
            Modifier
                .matchParentSize()
                .liveGlassLens(
                    hazeState = hazeState,
                    corner = corner,
                    frost = glassFrostTint(glass, isDark),
                    glass = glass,
                ),
        )
    } else if (hazeState != null && glass.hazeBlurDp > 0f) {
        Box(
            Modifier
                .matchParentSize()
                .glassFrost(state = hazeState, glass = glass, ground = frostBg, isDark = isDark),
        )
    }
    // Solid when the shader is really coming, faint when it is not.
    //
    // The slab is drawn at full opacity and the AGSL turns it into glass, and
    // that is the whole reason it looks like glass: the shader builds its bevel
    // and rim from the alpha heightfield underneath it. Panels in Tryptify used
    // to draw at a tenth of that as insurance — on a device where the shader
    // silently no-ops, a solid fill is left on screen as an opaque rounded
    // rectangle in the accent colour. The insurance worked and the cost was
    // that every panel that *did* have the shader had nearly no heightfield to
    // bevel, so it came out a soft smudge with no edge.
    //
    // Asking whether the shader will run replaces the guess, so the good case
    // gets the solid fill and the bad case still cannot paint a slab.
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .then(
                if (press != null) {
                    Modifier.glassSlab(
                        glass = glass,
                        tint = tint,
                        bulgeCenter = press.center,
                        bulgeAmount = { press.amount },
                        bulgeRadiusFraction = GlassPressDefaults.BULGE,
                        lensCorner = corner,
                        liveUnder = liveLens,
                    )
                } else {
                    Modifier.glassSlab(glass = glass, tint = tint, lensCorner = corner, liveUnder = liveLens)
                },
            ),
    ) {
        val r = corner.toPx().coerceAtMost(size.minDimension / 2f)
        drawRoundRect(
            color = if (shaded) tint else tint.copy(alpha = 0.14f),
            cornerRadius = CornerRadius(r, r),
        )
    }
}
