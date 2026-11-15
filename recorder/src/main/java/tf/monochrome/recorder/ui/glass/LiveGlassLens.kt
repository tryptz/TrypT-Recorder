package tf.monochrome.recorder.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeArea
import dev.chrisbanes.haze.HazeState
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * Glass that bends the real screen behind it, the way iOS draws it.
 *
 * The slab ([LIQUID_GLASS_SRC]) spends its one RenderEffect input on the alpha
 * heightfield its bevel is built from, so the backdrop it lenses has to be
 * reconstructed. The lens rim does not need a heightfield — it is computed
 * from the pane's corner — and that frees the input for the backdrop itself.
 *
 * So this layer draws Haze's own capture of the screen behind it (the
 * [HazeState] areas' content layers, the same recording the haze blur samples),
 * offset to where this pane sits, blurs it, and then runs [LIVE_LENS_SRC] over
 * the blurred result: blur first, then bend. What comes out is the live
 * backdrop, bent by the same rounded rim as the slab, under the same frost
 * tint the haze pane used.
 *
 * It replaces the haze pane under a slab; the slab still draws on top and
 * supplies the tint and the rim light.
 *
 * Same rule as any haze pane: it must be a sibling of the haze source, never
 * inside it. Drawing the source's layer from inside itself would recurse.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun Modifier.liveGlassLens(
    hazeState: HazeState,
    corner: Dp,
    frost: Color,
    glass: GlassSettings,
    /** The blur as a share of `hazeBlurDp`; see [LIVE_LENS_BLUR_SHARE]. */
    blurShare: Float = LIVE_LENS_BLUR_SHARE,
    /**
     * What lies behind the page, laid under the capture so the lens is opaque.
     * The same colour the haze pane it replaces used as `HazeStyle.backgroundColor`.
     */
    ground: Color = MaterialTheme.colorScheme.background,
): Modifier {
    val shader = liveLensShader ?: return this
    val view = LocalView.current
    // Where this pane is on screen. Written by layout, read by draw; not state,
    // because the pre-draw check below is what turns a change into a redraw.
    val anchor = remember { LensAnchor() }
    // Read in draw so that bumping it redraws this layer.
    val redraw = remember { mutableIntStateOf(0) }

    // The capture's display list updates itself — it is drawn here as a render
    // node, so a source that scrolls repaints this lens without any help. What
    // does not reach us on its own is geometry: the source or this pane moving,
    // or the source swapping its layer. Check those once a frame and redraw only
    // when one changed, so an idle screen stays idle.
    DisposableEffect(view, hazeState) {
        var last = 0L
        val listener = ViewTreeObserver.OnPreDrawListener {
            var sig = anchor.screen.hashCode().toLong()
            for (area in hazeState.areas) {
                sig = sig * 31 + area.positionOnScreen.hashCode()
                sig = sig * 31 + area.size.hashCode()
                sig = sig * 31 + System.identityHashCode(area.contentLayer)
            }
            if (sig != last) {
                last = sig
                redraw.intValue++
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }

    val shape = remember(corner) { lensClipShape(corner) }
    // The backdrop is blurred first, then bent. The blur runs over a margin of
    // the page around the pane as well as under it. That makes it a real
    // backdrop blur: the rim mixes in whatever lies just past the glass. Blurring
    // only the pane's own rectangle clamps at its edges, and smears the edge row
    // straight into the rim, which is the one band that refracts. So the
    // backdrop goes into its own layer, inflated by the margin, and the blur and
    // then the lens run there. The pane's clip trims it back to the glass.
    val backdrop = rememberGraphicsLayer()
    // Haze's capture holds only the page's content, not the app background
    // behind it, so it is transparent between the text. Drawn as is, the lens
    // was see-through exactly where it mattered: the blurred, bent copy of a
    // line of text lay over the real, sharp line underneath, and the sharp one
    // read straight through. The haze pane avoids this with an opaque
    // `HazeStyle.backgroundColor`; the lens lays [ground] down first.
    val pageColor = ground.copy(alpha = 1f)
    val effect = remember { LensEffectCache() }
    // The sources this pane has been seen inside. Its own layer re-records
    // apart from its source's (when it alone is invalidated), and Haze's
    // "recording now" flag is only up during the source's own recording, so
    // a source caught once is left out for good.
    val inside = remember { Collections.newSetFromMap(WeakHashMap<HazeArea, Boolean>()) }
    return this
        .onGloballyPositioned { anchor.screen = it.positionOnScreen() }
        .clip(shape)
        .drawBehind {
            redraw.intValue
            if (size.minDimension <= 0f) return@drawBehind
            // See LIVE_LENS_BLUR_SHARE for why a fifth. The setting is the only
            // say: 0 is crisp, unblurred refraction. A 6dp floor was tried and
            // taken out on device — at 0 the user wants clear glass.
            val blurPx = glass.hazeBlurDp * blurShare * density
            val blurOn = blurPx >= 0.5f
            // A gaussian reaches about 3 sigma, and Android's blur radius maps to
            // sigma ≈ 0.58 × radius + 0.5. Twice the radius plus 2px covers that.
            val margin = if (blurOn) ceil(blurPx * 2f + 2f) else 0f
            val (lensR, lensW) = lensRimPx(corner, size, glass.roundness)
            backdrop.renderEffect = effect.get(
                LensEffectKey(size, margin, blurPx, lensR, lensW, glass, frost),
            ) {
                val lens = liveLensEffect(
                    shader,
                    size = size,
                    margin = margin,
                    lensR = lensR,
                    lensW = lensW,
                    refraction = glass.refraction,
                    depth = glass.depth,
                    dispersion = glass.dispersion,
                    frost = frost,
                )
                // Chain order: the inner effect (the blur) runs first and the
                // outer one (the lens) bends its output.
                if (blurOn) {
                    RenderEffect.createChainEffect(
                        lens,
                        RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP),
                    )
                } else {
                    lens
                }.asComposeRenderEffect()
            }

            val windowId = view.windowId
            val here = anchor.screen
            var drew = 0
            val inflated = IntSize(
                ceil(size.width + 2f * margin).toInt(),
                ceil(size.height + 2f * margin).toInt(),
            )
            backdrop.record(inflated) {
                drawRect(pageColor)
                hazeState.areas
                    .filter { it.windowId == null || it.windowId == windowId }
                    // A source drawing its content right now is one this pane
                    // sits inside: drawn here, its layer would hold this pane,
                    // which holds it, and the render thread walked that loop
                    // until its stack ran out (a native crash, seen on device in
                    // Tryptify). Haze's own blur skips them the same way.
                    .filterNot { area ->
                        if (HazeContentDrawing.isDrawing(area)) inside.add(area)
                        area in inside
                    }
                    .sortedBy { it.zIndex }
                    .forEach { area ->
                        val layer = area.contentLayer ?: return@forEach
                        if (layer.isReleased) return@forEach
                        val at = area.positionOnScreen - here
                        translate(at.x + margin, at.y + margin) { drawLayer(layer) }
                        drew++
                    }
            }
            translate(-margin, -margin) { drawLayer(backdrop) }
            // With nothing to draw the lens is a frost over transparent: the
            // page shows through unbent. Say so once, so a report from a
            // device carries it in its recent log.
            if (drew == 0 && !LensDiagnostics.warnedEmpty) {
                LensDiagnostics.warnedEmpty = true
                Log.w(
                    LENS_TAG,
                    "no haze content layer to draw (areas=${hazeState.areas.size}, " +
                        "layers=${hazeState.areas.count { it.contentLayer != null }})",
                )
            }
        }
}

/** Everything the lens's RenderEffect is built from; a change rebuilds it. */
private data class LensEffectKey(
    val size: Size,
    val margin: Float,
    val blurPx: Float,
    val lensR: Float,
    val lensW: Float,
    val glass: GlassSettings,
    val frost: Color,
)

/**
 * The last RenderEffect and the key it was built from. The shader's uniforms
 * are read when the effect is created, so it is rebuilt whenever an input
 * changes. Otherwise the same effect is kept, so an idle frame allocates
 * nothing.
 */
private class LensEffectCache {
    private var key: LensEffectKey? = null
    private var value: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(
        k: LensEffectKey,
        build: () -> androidx.compose.ui.graphics.RenderEffect,
    ): androidx.compose.ui.graphics.RenderEffect {
        val v = value
        if (v != null && k == key) return v
        return build().also { key = k; value = it }
    }
}

/**
 * One pane's lens: every uniform [LIVE_LENS_SRC] declares, set on the shared
 * [shader], and the effect made from them. The only place an effect is made
 * from it; the parameters are the uniforms, in the shader's px, and none has
 * a default, so no pane can inherit another's (see [liveLensShader]).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun liveLensEffect(
    shader: RuntimeShader,
    size: Size,
    margin: Float,
    lensR: Float,
    lensW: Float,
    refraction: Float,
    depth: Float,
    dispersion: Float,
    frost: Color,
): RenderEffect {
    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uMargin", margin, margin)
    shader.setFloatUniform("uLensR", lensR)
    shader.setFloatUniform("uLensW", lensW)
    shader.setFloatUniform("uRefraction", refraction)
    shader.setFloatUniform("uDepth", depth)
    shader.setFloatUniform("uDispersion", dispersion)
    // Premultiplied, for a plain src-over in the shader. Zero: the live lens
    // is clear glass. Any frost veil read on device as a dull, frosted pane
    // over the backdrop, which was asked to go; the slab's thin tint on top is
    // the only colour the glass adds.
    val fa = frost.alpha * LIVE_LENS_FROST_SHARE
    shader.setFloatUniform("uFrost", frost.red * fa, frost.green * fa, frost.blue * fa, fa)
    return RenderEffect.createRuntimeShaderEffect(shader, "content")
}

/**
 * The clip for a lens of [corner], which may be [Dp.Infinity] for a disc or a
 * pill. `RoundedCornerShape(Dp.Infinity)` cannot be drawn: Compose scales
 * corners that overflow the box by minDimension / their sum, which for
 * infinite corners is 0, and infinity × 0 is NaN — it throws on the first
 * frame. Tryptify's full player disc crashed exactly that way. 50% is the same
 * shape with finite corners.
 */
internal fun lensClipShape(corner: Dp): Shape =
    if (corner.value.isFinite()) RoundedCornerShape(corner) else RoundedCornerShape(percent = 50)

/** Off restores the haze pane under every glass pane, exactly. */
internal const val LIVE_LENS_GLASS = true

private const val LENS_TAG = "LiveGlassLens"

/**
 * Haze's own "this source is recording its content" flag on a [HazeArea],
 * which its blur reads to leave out a source it sits inside. Internal to
 * Haze, so read by its compiled name, kept through R8 (proguard-rules.pro).
 * Where it cannot be read nothing is left out.
 */
internal object HazeContentDrawing {
    private val getter: java.lang.reflect.Method? = runCatching {
        HazeArea::class.java.getMethod("getContentDrawing\$haze_release")
    }.onFailure {
        Log.w(LENS_TAG, "Haze's contentDrawing flag is not readable; a lens inside its source would loop", it)
    }.getOrNull()

    fun isDrawing(area: HazeArea): Boolean =
        getter?.let { runCatching { it.invoke(area) as? Boolean }.getOrNull() } ?: false
}

/** One-shot flags for the lens's own diagnostics, so they log once per process. */
private object LensDiagnostics {
    @Volatile var warnedEmpty = false
}

/**
 * The live lens's blur as a share of the haze pane's (`hazeBlurDp`): a fifth,
 * about 6.3dp on Float. Glass frosts what is behind it as well as bending it —
 * at 2-3dp page text read straight through and fought the labels on top. Much
 * more and there is no detail left to bend: at 10dp the rim's bend all but
 * vanished (checked offline before shipping in Tryptify). Everything shares it.
 */
private const val LIVE_LENS_BLUR_SHARE = 0.2f

/** The live lens's frost as a share of the haze pane's tint alpha: none, clear glass. */
private const val LIVE_LENS_FROST_SHARE = 0f

private class LensAnchor {
    var screen: Offset = Offset.Zero
}

/**
 * Whether [LIVE_LENS_SRC] compiles here: whether there is a [liveLensShader].
 * Asked on every Android version, so the version goes first: below 13 there
 * is no RuntimeShader class for reading the shader to cast to.
 */
internal val liveLensCompiles: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && liveLensShader != null

/**
 * [LIVE_LENS_SRC], compiled once for the whole process and shared by every
 * lens. Null below API 33, or where it will not compile.
 *
 * Shared on the same terms as the slab's shader (see `liquidGlassShader`): an
 * effect copies the uniforms as it is made, every effect is made by
 * [liveLensEffect], which sets all of them first, and it is never a paint
 * shader. One per pane would be a fresh SkSL parse on the main thread whenever
 * a pane came into composition.
 */
private val liveLensShader: RuntimeShader? by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        null
    } else {
        runCatching { RuntimeShader(LIVE_LENS_SRC) }
            .onFailure { Log.w(LENS_TAG, "live lens shader did not compile: ${it.message}") }
            .getOrNull()
    }
}

// The live backdrop, bent by the lens rim. `content` is the screen behind the
// pane and a margin of uMargin around it, already blurred by the chained
// effect. p is in that inflated layer's px; p - uMargin is the pane's own.
//
// The bend is [LIQUID_GLASS_SRC]'s with a lens rim, term for term: the same
// rounded-rim normal, Snell at eta 0.66 with the same per-channel dispersion,
// and the same pixel scale — refraction × rim width × 5 — so this layer and the slab
// on top of it move together. A convex rim bends rays inward, so every sample
// lands inside the pane and the layer's own bounds are always enough.
private const val LIVE_LENS_SRC = """
$LENS_RIM_SKSL
uniform shader content;
uniform float2 uSize;
uniform float2 uMargin;       // backdrop margin around the pane, px each side
uniform float uLensR;
uniform float uLensW;
uniform float uRefraction;
uniform float uDepth;
uniform float uDispersion;
uniform float4 uFrost;        // premultiplied tint laid over the bent backdrop

half4 main(float2 p) {
    float4 c;
    if (uLensW < 0.5) {
        c = float4(content.eval(p));
    } else {
        float3 N = normalize(float3(lensRimSlope(p - uMargin, uSize, uLensR, uLensW) * uDepth, 1.0));
        float3 I = float3(0.0, 0.0, -1.0);
        float ds = 0.06 * uDispersion;
        float k = uRefraction * uLensW * 5.0;
        float4 cR = float4(content.eval(p + refract(I, N, 0.66 - ds).xy * k));
        float4 cG = float4(content.eval(p + refract(I, N, 0.66).xy * k));
        float4 cB = float4(content.eval(p + refract(I, N, 0.66 + ds).xy * k));
        c = float4(cR.r, cG.g, cB.b, cG.a);
    }
    return half4(uFrost + c * (1.0 - uFrost.a));
}
"""
