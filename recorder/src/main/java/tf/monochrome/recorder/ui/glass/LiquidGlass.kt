package tf.monochrome.recorder.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.core.graphics.createBitmap
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.max
import kotlin.math.min

/*
 * "Liquid glass": an AGSL RenderEffect that turns an already-drawn slab into
 * real, refractive glass. The bevel normal is derived from the slab's own
 * alpha field (plus, for a rounded rect, a lens rim as wide as its corner);
 * the body is rendered see-through, a smooth tinted backdrop is reconstructed
 * and *lensed* through that bevel (with chromatic aberration), and a bright
 * specular rim rides the beveled edge. Nothing travels across the surface:
 * the living motion undulates in place, so the glass never draws the eye.
 *
 * Requires API 33 (RuntimeShader); below that, or if the shader fails to
 * compile on some GPU driver, the modifier is a no-op and the pane falls back
 * (see GlassPanel.kt's glassBase).
 */

private const val TAG = "LiquidGlass"

// One process-wide epoch so every rememberFrameSeconds() instance reports the
// same timeline: a surface composed later continues the animation phase
// instead of restarting it from zero.
@Volatile
private var frameClockEpochNanos = -1L

/**
 * The single state object every glass surface animates from.
 *
 * Sharing one holder makes one write and one invalidation scope per frame
 * however many panes are on screen. The per-surface `withFrameNanos` loops
 * stay (they are a single Choreographer callback list, and they all compute
 * the same value, so the redundant writes are equal-valued and Compose's
 * structural-equality policy drops them for free).
 */
private val frameClockSeconds = mutableFloatStateOf(0f)

/**
 * Per-frame clock in seconds on a shared app-wide timeline. Read it from draw
 * or layout lambdas (graphicsLayer, drawBehind) so animation never recomposes.
 *
 * Pass `animated = false` when the calling surface's shader has no live
 * time-varying term — every `uTime` use in [LIQUID_GLASS_SRC] is scaled by
 * `uLiquid`, so a surface at `surfaceMotion = 0` renders identically frame to
 * frame and has no reason to drive one. Without the gate a strip of glass that
 * isn't moving pins the process at display refresh rate. The `if` is
 * deliberate — flipping [animated] recomposes, which enters or leaves the
 * effect, so the loop starts and stops with it.
 */
@Composable
internal fun rememberFrameSeconds(animated: Boolean = true): State<Float> {
    if (animated) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { now ->
                    if (frameClockEpochNanos < 0L) frameClockEpochNanos = now
                    frameClockSeconds.floatValue = (now - frameClockEpochNanos) / 1_000_000_000f
                }
            }
        }
    }
    return frameClockSeconds
}

/**
 * Whether the slab shader will actually do anything for a pane of [glass].
 *
 * The slab modifier is a silent no-op when glass is switched off, below API 33,
 * or on a device whose driver will not compile the shader — and a caller cannot
 * otherwise tell. That matters because the slab a pane draws underneath it is
 * meant to be turned *into* glass: at full opacity it is a solid rounded
 * rectangle until the shader bevels it, so the honest choice of fill depends on
 * whether the shader is coming.
 *
 * This existed as a guess in Tryptify before, and the guess was to draw the
 * slab at a tenth of its opacity so the failure mode would be a faint tint.
 * That made every pane that *did* have the shader look nothing like glass: the
 * shader builds its bevel and rim from the alpha heightfield of what is under
 * it, and a near-transparent fill gives it almost no heightfield to read — a
 * soft smudge with no edge instead of a pane.
 *
 * The compile attempt is remembered unconditionally, before any of the state
 * that can change, so this never alters the shape of a caller's composition.
 */
@Composable
internal fun rememberLiquidGlassAvailable(glass: GlassSettings): Boolean {
    val compiles = remember { liquidGlassCompiles }
    return compiles && glass.enabled
}

/**
 * Whether [LIQUID_GLASS_SRC] compiles on this device: whether there is a
 * [liquidGlassShader].
 *
 * Asked on every Android version, so the version goes first: below 13 the
 * RuntimeShader class does not exist, and reading the shader at all would
 * cast to it.
 */
private val liquidGlassCompiles: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && liquidGlassShader != null

/**
 * [LIQUID_GLASS_SRC], compiled once for the whole process and shared by every
 * pane and button that draws glass. Null below API 33, or where the driver
 * will not take it.
 *
 * One shader per call site would be a full SkSL parse of a large shader on the
 * main thread for every pane that comes into composition, on exactly the
 * frames where a screen is being built.
 *
 * Sharing it is safe for one reason, and only while it holds: an effect takes
 * its own copy of the uniforms and the bound child shaders when it is made, so
 * a pane's effect keeps its values after the next pane sets its own. Every
 * pane already relies on that from one frame to the next, with the last
 * frame's effect still on the render thread while the next frame sets new
 * values. Two things keep it true:
 * - every effect is made by [liquidGlassEffect], which sets every uniform the
 *   shader declares and binds `uArt` first, so no pane inherits the last
 *   pane's values (a fresh shader started at zero; this one never does);
 * - it is only ever a RenderEffect, made in a layer block on the main
 *   thread. A Paint or brush shader is read later on the render thread, by
 *   which time another pane may have changed it.
 * `SharedShaderUniformsTest` holds both.
 */
private val liquidGlassShader: RuntimeShader? by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        null
    } else {
        runCatching { RuntimeShader(LIQUID_GLASS_SRC) }
            .onFailure { Log.w(TAG, "liquid-glass shader did not compile: ${it.message}") }
            .getOrNull()
    }
}

/**
 * The wash that goes over the blur — the *frost*, as opposed to the haze.
 *
 * One function so every sheet of glass frosts by the same rule, scaled by the
 * material's own [GlassSettings.hazeTint].
 *
 * The light-theme wash used to be nearly twice the dark one (0.45 white
 * against 0.32 black). On a light or warm theme that is most of a solid
 * colour laid over the blur, which is why those panels read as milky slabs
 * rather than glass — the blurred backdrop was there, just buried. The two
 * sides are even now; a heavier look is a higher hazeTint, which reaches 2.0.
 */
internal fun glassFrostTint(glass: GlassSettings, isDark: Boolean): Color {
    val base = if (isDark) Color.Black.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.26f)
    return base.copy(alpha = (base.alpha * glass.hazeTint).coerceIn(0f, 1f))
}

/**
 * A haze pane frosted by [glass]: its backdrop blur, and [glassFrostTint] over
 * it toward [ground]. The one recipe every frosted pane uses.
 *
 * This is the haze — an actual gaussian blur of whatever [state] captured — as
 * distinct from the frost, which is the tint carried on top of it. Both are
 * here because a sheet of real glass is both: you see the blurred room through
 * it *and* it has a colour.
 */
internal fun Modifier.glassFrost(
    state: HazeState,
    glass: GlassSettings,
    ground: Color,
    isDark: Boolean = ground.luminance() <= 0.5f,
): Modifier = hazeEffect(
    state = state,
    style = HazeStyle(
        backgroundColor = ground,
        blurRadius = glass.hazeBlurDp.dp,
        tints = listOf(HazeTint(glassFrostTint(glass, isDark))),
        noiseFactor = 0f,
    ),
)

/**
 * The refractive glass ([LIQUID_GLASS_SRC]) over the slab this layer draws.
 * Apply it to a layer that draws a *solid* slab so the shader bevels that
 * shape. No-op when [glass] is off, below API 33, or on shader-compile failure.
 */
@Composable
internal fun Modifier.glassSlab(
    glass: GlassSettings,
    tint: Color,
    bulgeCenter: Offset = Offset(0.5f, 0.5f),
    bulgeAmount: () -> Float = { 0f },
    /**
     * How wide the press dome is, as a fraction of the pane's longest side.
     *
     * Zero keeps the shader's own default of a sixth of the width, which is
     * meant to pick out one button on a bar of several. A pane that is
     * *itself* the button wants the swell across the whole of it, and a sixth
     * of the width on a full-width sheet is a dimple nobody can see.
     */
    bulgeRadiusFraction: Float = 0f,
    /**
     * The corner radius of the slab this layer draws, when that slab is a
     * rounded rect filling the layer — [Dp.Infinity] for a pill or a disc.
     *
     * Set, the shader gives the pane a lens rim as wide as its corner, which is
     * what makes the backdrop bend toward the edge the way a real pane does.
     * Unspecified keeps the alpha-only bevel, the right shape for a glyph or an
     * icon; a slab that is not a rounded rect must leave it unspecified, or the
     * rim lands where its edge is not.
     */
    lensCorner: Dp = Dp.Unspecified,
    /**
     * True when a [liveGlassLens] draws the real backdrop under this slab. The
     * slab then drops its stand-in refraction and keeps only a thin tint and
     * the rim light, so the bent backdrop is what shows through.
     */
    liveUnder: Boolean = false,
): Modifier {
    if (!glass.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return glassSlabLayer(tint, glass, bulgeCenter, bulgeAmount, bulgeRadiusFraction, lensCorner, liveUnder)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.glassSlabLayer(
    tint: Color,
    g: GlassSettings,
    bulgeCenter: Offset,
    bulgeAmount: () -> Float,
    bulgeRadiusFraction: Float,
    lensCorner: Dp,
    liveUnder: Boolean,
): Modifier {
    val shader = liquidGlassShader ?: return this
    // Every uTime term in the shader is scaled by uLiquid, so at zero motion it
    // renders identically frame to frame and has no reason to drive a clock.
    val timeSec = rememberFrameSeconds(animated = g.surfaceMotion > 0f)
    return this.graphicsLayer {
        if (size.minDimension > 0f) {
            val (lensR, lensW) = lensRimPx(lensCorner, size, g.roundness)
            renderEffect = liquidGlassEffect(
                shader,
                size = size,
                time = timeSec.value,
                // No gravity sensor here: every uTilt term is also multiplied by
                // uTiltAmount, and both are zero, exactly as Tryptify's FLOAT.
                tilt = Offset.Zero,
                tint = tint,
                tint2 = tint,
                backdropMix = 0f,
                bodyOpacity = g.bodyOpacity,
                refraction = g.refraction,
                // Rim fades out with the last stretch of body opacity: at 0 the
                // body is invisible and a full-strength rim would leave floating
                // box outlines around every plate. Above 0.15 body the rim is
                // untouched.
                rimGain = g.rimBrightness * (g.bodyOpacity / 0.15f).coerceIn(0f, 1f),
                dispersion = g.dispersion,
                sampleRings = g.sampleRings.toFloat(),
                roundness = g.roundness,
                depth = g.depth,
                // Gloss maps to a specular exponent (20 = soft/frosted-wide ..
                // 260 = tight mirror), edge width to the Fresnel falloff (8 =
                // thin crisp .. 2 = broad shoulder).
                liquid = g.surfaceMotion,
                reflection = g.reflection,
                gloss = 20f + 240f * g.gloss,
                tiltAmount = 0f,
                lightAngle = g.lightAngleDeg * 0.017453292f,
                fresnelPower = 8f - 6f * g.edgeWidth,
                frost = g.frost,
                bulge = bulgeCenter,
                bulgeAmount = bulgeAmount(),
                bulgeRadius = if (bulgeRadiusFraction > 0f) {
                    max(size.width, size.height) * bulgeRadiusFraction
                } else {
                    0f
                },
                lensR = lensR,
                lensW = lensW,
                liveUnder = if (liveUnder) 1f else 0f,
            )
        }
    }
}

/**
 * One pane's glass this frame: every uniform [LIQUID_GLASS_SRC] declares, set
 * on the shared [shader], and the effect made from them.
 *
 * This is the only place an effect is made from it. The parameters are the
 * uniforms, already in the shader's units, and none has a default, so a pane
 * cannot leave one out: whatever it left unset it would inherit from the last
 * pane that drew. Nothing else runs between the first uniform and the effect,
 * which takes its copy of them as it is made. A new uniform in the shader
 * needs a parameter here; `SharedShaderUniformsTest` fails until it has one.
 *
 * `uArt` is bound on every path (SkSL fails the draw on an unbound child), and
 * [setNoRayLight] gives every pane the god rays' neutral values.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun liquidGlassEffect(
    shader: RuntimeShader,
    size: Size,
    time: Float,
    tilt: Offset,
    tint: Color,
    tint2: Color,
    backdropMix: Float,
    bodyOpacity: Float,
    refraction: Float,
    rimGain: Float,
    dispersion: Float,
    sampleRings: Float,
    roundness: Float,
    depth: Float,
    liquid: Float,
    reflection: Float,
    gloss: Float,
    tiltAmount: Float,
    lightAngle: Float,
    fresnelPower: Float,
    frost: Float,
    bulge: Offset,
    bulgeAmount: Float,
    bulgeRadius: Float,
    lensR: Float,
    lensW: Float,
    liveUnder: Float,
): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uTime", time)
    shader.setFloatUniform("uTilt", tilt.x, tilt.y)
    shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
    shader.setFloatUniform("uTint2", tint2.red, tint2.green, tint2.blue)
    shader.setFloatUniform("uBackdropMix", backdropMix)
    shader.bindBackdropArt()
    shader.setFloatUniform("uBodyOpacity", bodyOpacity)
    shader.setFloatUniform("uRefraction", refraction)
    shader.setFloatUniform("uRimGain", rimGain)
    shader.setFloatUniform("uDispersion", dispersion)
    shader.setFloatUniform("uSampleRings", sampleRings)
    shader.setFloatUniform("uRoundness", roundness)
    shader.setFloatUniform("uDepth", depth)
    shader.setFloatUniform("uLiquid", liquid)
    shader.setFloatUniform("uReflection", reflection)
    shader.setFloatUniform("uGloss", gloss)
    shader.setFloatUniform("uTiltAmount", tiltAmount)
    shader.setFloatUniform("uLightAngle", lightAngle)
    shader.setFloatUniform("uFresnelPower", fresnelPower)
    shader.setFloatUniform("uFrost", frost)
    shader.setFloatUniform("uBulge", bulge.x, bulge.y)
    shader.setFloatUniform("uBulgeAmt", bulgeAmount)
    shader.setFloatUniform("uBulgeR", bulgeRadius)
    shader.setFloatUniform("uLensR", lensR)
    shader.setFloatUniform("uLensW", lensW)
    shader.setFloatUniform("uLiveUnder", liveUnder)
    setNoRayLight(shader)
    return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}

/** Glass with no god rays to catch: every pane. Bit-identical to before they existed. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun setNoRayLight(shader: RuntimeShader) {
    shader.setFloatUniform("uRayLight", 0f, 0f, 1f)
    shader.setFloatUniform("uRayAmount", 0f)
    shader.setFloatUniform("uRayColor", 0f, 0f, 0f)
    shader.setFloatUniform("uRayReach", 1f)
    shader.setFloatUniform("uRayDecay", 1f)
    shader.setFloatUniform("uRayBack", 0f)
}

/**
 * Binds the real-backdrop uniforms for one pane — always to "no art": the
 * recorder has no cover to lens, so this is Tryptify's no-art path alone.
 *
 * `uArt` is set even though it is not used: SkSL requires every child shader
 * to have an input, and an unbound one is a draw-time failure rather than a
 * blank sample. [EMPTY_ART] is the stand-in. A mix of zero leaves the shader
 * on its reconstructed field, which is the glass every Tryptify pane without
 * a cover draws.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.bindBackdropArt() {
    setInputShader("uArt", EMPTY_ART)
    setFloatUniform("uArtScrim", NO_ART_SCRIM.red, NO_ART_SCRIM.green, NO_ART_SCRIM.blue)
    setFloatUniform("uArtMix", 0f)
    setFloatUniform("uArtRect", 0f, 0f, 1f, 1f)
    setFloatUniform("uArtScreen", 0f, 0f, 1f, 1f)
}

/**
 * The scrim tone a Tryptify pane is handed when there is no album behind it:
 * its default backdrop's dominant (0xFF101018) darkened as `backdropScrimTone`
 * darkens it. Unread at uArtMix 0, but set to the same value all the same.
 */
private val NO_ART_SCRIM: Color = lerp(Color(0xFF101018), Color.Black, 0.62f)

private val EMPTY_ART: Shader by lazy {
    BitmapShader(
        createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        Shader.TileMode.CLAMP,
        Shader.TileMode.CLAMP,
    )
}

/** The widest a lens rim gets, however large the corner it sits in. */
internal val LensRimMax = 24.dp

// The lens rim, shared by the slab glass and the live lens so the two bend
// identically. For a rounded rect of [size] with corner [r], it takes the exact
// distance to the edge and lays a rounded (circular) edge across a band [w]
// wide, h = sqrt(1 - (1-x)^2): vertical at the rim, easing to flat by the
// inner edge, so the backdrop bends hardest at the rim and not at all across
// the middle. Circular, not a squircle: the squircle (1-(1-x)^4)^(1/4) is flat
// for most of its width, so only the outermost few pixels bent and on device
// the refraction read as too weak; at the band's midpoint this has ~4x the
// slope. Returns the surface slope, pointing outward; the caller scales it
// by depth and adds it to the normal's xy. w <= r keeps the band inside the
// corner arcs, where the distance field has no mitre crease.
internal const val LENS_RIM_SKSL = """
float2 lensRimSlope(float2 p, float2 size, float r, float w) {
    float2 hs = size * 0.5;
    float2 c = p - hs;
    float2 q = abs(c) - (hs - r);
    float2 qp = max(q, float2(0.0));
    float d = length(qp) + min(max(q.x, q.y), 0.0) - r;   // < 0 inside
    // Outward direction of the nearest edge (the distance field's gradient).
    float2 n = (max(q.x, q.y) > 0.0) ? qp / max(length(qp), 1e-4)
             : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
    n *= float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);
    float m = 1.0 - clamp(-d / w, 0.0, 1.0);
    float m3 = m * m * m;
    // dh/dx of the circular edge; unbounded at the rim, so capped.
    float slope = m / sqrt(max(1.0 - m * m, 1e-3));
    return n * min(slope, 6.0);
}
"""

/**
 * The lens rim's corner and band width in px, as `uLensR`/`uLensW` take them.
 * The corner is clamped to the short half-side, so a pill or disc can pass
 * [Dp.Infinity]. The band is at most the corner (no crease) and at most
 * [LensRimMax], so a tall pill keeps a flat middle; roundness widens it,
 * 0.5..2 → 5/8..all of that. Unspecified gives (0, 0): no rim.
 */
internal fun Density.lensRimPx(corner: Dp, size: Size, roundness: Float): Pair<Float, Float> {
    if (!corner.isSpecified) return 0f to 0f
    val r = min(corner.value * density, size.minDimension / 2f)
    return r to min(r, LensRimMax.toPx()) * (0.5f + 0.25f * roundness)
}

// True refractive glass. Output stays in premultiplied alpha (RenderEffect
// contract): the final rgb is clamped to <= the emitted alpha, so anti-aliased
// glyph edges remain valid and halo-free. The glyph body is emitted at reduced
// alpha (the backdrop shows through), while the beveled rim rises back to full
// alpha so its bright specular can read as a crisp glass edge.
//
// Smooth-backdrop note: the field the glass refracts is reconstructed in-shader
// (a soft vertical wash + top glow + two off-axis pools, album-tinted) to
// mirror the smooth gradient that actually sits behind the lyrics. Because that
// real backdrop is low-frequency, a reconstructed field lenses
// indistinguishably from sampling the real pixels — and it needs no fragile
// per-frame backdrop capture.
//
// Optics model (three layers, all riding the existing uniforms):
//  - bevel refraction: Snell bend where the alpha-field normal turns (edges);
//  - interior slab parallax: tilt/drift offset of the backdrop across FLAT
//    faces, scaling with uRefraction — the "thickness" a bevel-only model
//    lacks;
//  - liquid: fast edge shimmer (edge-gated, three interfering octaves with a
//    slow breath) + a slow ~600px face swell (ungated, far too broad to
//    lattice a button disc);
//  - shine: the glint twinkles in hash-staggered 4px cells with a slowly
//    cycling chromatic spread — scaled by uLiquid so surfaceMotion = 0 presets
//    stay perfectly still. No pass travels across the pane; see the shader's
//    own note where the light sheet used to be.
private const val LIQUID_GLASS_SRC = """
$LENS_RIM_SKSL
uniform shader content;
uniform float2 uSize;
uniform float uTime;
uniform float2 uTilt;
uniform float3 uTint;
uniform float uBodyOpacity;   // glass body opacity (lower = more see-through)
uniform float uRefraction;    // how hard the bevel lenses the backdrop
uniform float uRimGain;       // brightness of the specular glass edge
uniform float uDispersion;    // chromatic aberration at the refracting edges
uniform float uSampleRings;   // bevel sample rings 1/2/3 → 5/9/13 taps per pixel
uniform float3 uTint2;        // second album tone (blurred-art backdrop)
uniform float uBackdropMix;   // 0 = flat single-tint wash; >0 = lens the album art
uniform float uRoundness;     // bevel shoulder width: 1 = neutral, higher = rounder/softer edge
uniform float uDepth;         // profondeur: 1 = neutral, higher = steeper relief / more 3D
uniform float uLiquid;        // surface unrest: 1 = full moving sheen, lower = calmer, cleaner glass
uniform float uReflection;    // environment ("room") reflection strength
uniform float uGloss;         // specular exponent: higher = tighter, mirror-polished glint
uniform float uTiltAmount;    // how strongly device tilt moves the light/reflection
uniform float uLightAngle;    // key-light direction, radians
uniform float uFresnelPower;  // Fresnel falloff: lower = broader reflective rim
uniform float uFrost;         // frosted roughness: 0 = clear, higher = misted
uniform float2 uBulge;        // press-bulge centre, normalized (0..1) in the surface
uniform float uBulgeAmt;      // press-bulge swell, 0 = none .. 1 = full dome
uniform float uBulgeR;        // press-bulge dome radius in px; <=0 falls back to uSize.x/6
uniform float uLensR;         // lens rim: the slab's corner radius in px (rounded rect filling uSize)
uniform float uLensW;         // lens rim: bevel band width in px, <= uLensR; 0 = alpha-only bevel
uniform float uLiveUnder;     // 1 = a LiveGlassLens draws the real backdrop under this slab

// The lyric god rays' light, for glass letters to catch (LyricRayLight). A
// point light at the rays' own position, so a glint sits on the bevels that
// face the shafts' source and moves with it. uRayAmount = 0 — every pane, and
// lyrics without rays — leaves every pixel bit-identical.
uniform float3 uRayLight;     // xy = the light's point on this layer, px; z = how far it stands off the glass
uniform float uRayAmount;     // how much of it the glass catches
uniform float3 uRayColor;     // the light's colour
uniform float uRayReach;      // px over which it fades: the shafts' length
uniform float uRayDecay;      // the shafts' decay per 1/50 of uRayReach
uniform float uRayBack;       // 1 = the light is behind the glass, and glows through its rims

// The real backdrop, when there is one to lens. uArt is ALWAYS bound (SkSL
// requires every child shader to be set); uArtMix is what decides whether it
// is used, so a pane with no decoded cover pays one uniform write and nothing
// else. See GlassBackdropArt.kt for why this is a bitmap and not a live layer.
uniform shader uArt;          // artwork pixels behind this pane
uniform float uArtMix;        // 0 = reconstructed field only, 1 = the real thing
uniform float4 uArtRect;      // this pane inside the art: xy = origin, zw = size, art px
uniform float4 uArtScreen;    // this pane inside the root layout, normalized 0..1
uniform float3 uArtScrim;     // the scrim's mid-screen album tone (already darkened)

// Smooth album-tinted backdrop field, reconstructed so the glass can lens it.
// Returns a 0..1 luminance weight for the tint at uv (matches the vertical
// wash + soft top glow drawn behind the lyrics). Two soft off-axis pools give
// the field low-frequency STRUCTURE: a featureless gradient displaces into
// itself and refraction reads as nothing — these pools are what make the
// lensing visible while staying smooth enough to pass for the real backdrop.
float backdropField(float2 uv) {
    float wash = mix(0.45, 0.0, clamp(uv.y, 0.0, 1.0));
    float glow = smoothstep(1.0, 0.0, distance(uv, float2(0.5, 0.22)) * 1.5);
    float pool1 = smoothstep(0.85, 0.0, distance(uv, float2(0.22, 0.65))) * 0.16;
    float pool2 = smoothstep(0.95, 0.0, distance(uv, float2(0.80, 0.38))) * 0.12;
    return clamp(wash + glow * 0.5 + pool1 + pool2, 0.0, 1.0);
}

// Album tone the glass lenses at uv. With the blurred cover behind the lyrics
// (uBackdropMix > 0) this is a two-tone album blend, so the refraction carries
// real colour variation like a photo behind glass. The blend runs mostly down
// the surface with a slight diagonal lean, so HORIZONTAL displacement also
// crosses a colour boundary (a pure-vertical blend made sideways lensing
// invisible). With no blurred art (uBackdropMix = 0) it collapses to uTint —
// identical to the old single tone.
float3 backdropTintAt(float2 uv) {
    float t = clamp(uv.y * 0.82 + uv.x * 0.18, 0.0, 1.0);
    float3 two = mix(uTint, uTint2, smoothstep(0.0, 1.0, t));
    return mix(uTint, two, uBackdropMix);
}

// PlayerBlurredArtBackground lays a heavy vertical scrim over the stretched
// cover — black at 0.58 up top, a darkened album tone at 0.52 through the
// middle, black at 0.72 at the foot — so what is actually on screen is a good
// deal darker than the artwork itself. Lensing the raw cover would light every
// pane up like a lamp. This puts the same scrim back, and does it in SCREEN
// space (uArtScreen), because the gradient runs down the display, not down the
// pane: two panes at different heights must come out differently dark.
float3 artScrimmed(float2 uv, float3 art) {
    float y = clamp(uArtScreen.y + clamp(uv.y, 0.0, 1.0) * uArtScreen.w, 0.0, 1.0);
    float top = 1.0 - smoothstep(0.0, 0.5, y);
    float bot = smoothstep(0.5, 1.0, y);
    float mid = 1.0 - top - bot;
    float keep = 0.42 * top + 0.48 * mid + 0.28 * bot;
    return art * keep + uArtScrim * (0.52 * mid);
}

// The screen behind this pane, in the artwork's own pixels. uArtRect carries
// the pane's rect there (see backdropArtRect), so a control low on the screen
// lenses the part of the cover actually behind it rather than the whole image
// squashed into its bounds — the difference between glass and a decal.
//
// Sampler output is premultiplied, so it is divided back out: an opaque cover
// is unchanged by that, and one with transparent corners stops fringing dark.
float3 artAt(float2 uv) {
    float4 c = float4(uArt.eval(uArtRect.xy + uv * uArtRect.zw));
    float3 rgb = (c.a > 0.003) ? c.rgb / c.a : float3(0.0);
    return artScrimmed(uv, rgb);
}

// Procedural studio environment, sampled by the reflection vector. A soft
// vertical light gradient plus a moving key light and a cooler fill, so the
// glass catches a believable "room" that streaks across the bevel as the
// surface normal turns — instead of one flat, generic highlight. Device tilt
// and a slow drift move the lights so the reflections stay alive.
float3 environment(float3 r, float2 tilt, float2 keyDir, float t, float liquid) {
    float2 d = r.xy;
    // Screen y is down, so the bright sky is where the reflection points up
    // (r.y < 0): a soft top->bottom studio gradient.
    float up = clamp(0.5 - 0.5 * r.y, 0.0, 1.0);
    float3 sky = mix(float3(0.05, 0.06, 0.08), float3(0.82, 0.88, 1.0),
                     smoothstep(0.12, 0.96, up));
    // Bright soft key light placed along the chosen light angle (keyDir),
    // nudged by device tilt + a slow drift.
    float2 key = keyDir + tilt * 0.5
               + 0.06 * float2(sin(t * 0.40), cos(t * 0.33)) * liquid;
    float keyI = smoothstep(0.55, 0.0, distance(d, key));
    // Cooler fill light from the opposite corner.
    float2 fill = -keyDir * 0.85 - tilt * 0.4;
    float fillI = smoothstep(0.72, 0.0, distance(d, fill)) * 0.5;
    return sky + float3(1.0, 0.97, 0.92) * keyI * 1.7
              + float3(0.65, 0.82, 1.0) * fillI;
}

half4 main(float2 p) {
    half4 src = content.eval(p);
    float a = float(src.a);
    // Outside the glyphs the surface is empty, so the glass exists only where a
    // letter is: hand those pixels straight back (transparent) and the real
    // backdrop behind the lyric layer shows through untouched.
    if (a < 0.004) {
        return src;
    }

    // Bevel normals from the glyph alpha field. The sample count is a user
    // setting (uSampleRings): the fine cross is always taken; the broad ring and
    // the diagonal ring are gated so lower quality skips those texture fetches —
    // fewer taps per pixel is a real GPU saving.
    // Roundness widens the taps: a broader sampling radius reads the alpha edge
    // over a wider band, so the bevel shoulder rolls off round and pillowy
    // instead of a tight, sharp edge (rr = 1 reproduces the original look).
    float rr = uRoundness;
    float aL1 = float(content.eval(p + float2(-1.25 * rr, 0.0)).a);
    float aR1 = float(content.eval(p + float2( 1.25 * rr, 0.0)).a);
    float aU1 = float(content.eval(p + float2(0.0, -1.25 * rr)).a);
    float aD1 = float(content.eval(p + float2(0.0,  1.25 * rr)).a);
    float2 grad = float2(aL1 - aR1, aU1 - aD1);

    if (uSampleRings > 1.5) {
        // Broad ring: rounder, softer bevel.
        float aL2 = float(content.eval(p + float2(-2.5 * rr, 0.0)).a);
        float aR2 = float(content.eval(p + float2( 2.5 * rr, 0.0)).a);
        float aU2 = float(content.eval(p + float2(0.0, -2.5 * rr)).a);
        float aD2 = float(content.eval(p + float2(0.0,  2.5 * rr)).a);
        grad += 0.4 * float2(aL2 - aR2, aU2 - aD2);
    }
    if (uSampleRings > 2.5) {
        // Diagonal ring: smoother normals on curved strokes.
        float aNW = float(content.eval(p + float2(-1.8 * rr, -1.8 * rr)).a);
        float aNE = float(content.eval(p + float2( 1.8 * rr, -1.8 * rr)).a);
        float aSW = float(content.eval(p + float2(-1.8 * rr,  1.8 * rr)).a);
        float aSE = float(content.eval(p + float2( 1.8 * rr,  1.8 * rr)).a);
        grad += 0.3 * float2((aNW + aSW) - (aNE + aSE),
                             (aNW + aNE) - (aSW + aSE));
    }

    // Liquid, two scales:
    // 1) Edge shimmer — the original fine ripple, gated by the real bevel
    //    strength (the geometric gradient BEFORE the ripple) so the fast
    //    undulation only lives where there already IS an edge. Ungated, a big
    //    flat face — a button disc, the dock slab — became a lattice of ripple
    //    highlights. Thin glyphs are all edge, so lyrics keep their liquid life.
    float edge = clamp(length(grad) * 1.5, 0.0, 1.0);
    float w1 = sin(p.x * 0.055 + uTime * 1.7) * cos(p.y * 0.081 - uTime * 1.3);
    float w2 = sin((p.x + p.y) * 0.035 - uTime * 0.9);
    // A third, counter-running octave breaks the two-wave moiré into organic
    // caustic-like interference, and a slow breath swells the whole shimmer in
    // and out so the motion never settles into a loop the eye can lock onto.
    float w3 = sin(p.y * 0.047 - p.x * 0.021 + uTime * 2.3);
    float breathe = 0.8 + 0.2 * sin(uTime * 0.7);
    grad += (0.04 * float2(w1, w2) + 0.02 * float2(w3, -w3))
            * uLiquid * a * edge * breathe;
    // 2) Face swell — a much longer wavelength (~600px vs ~100px) at a quarter
    //    of the amplitude, NOT edge-gated. This is what makes a flat pane read
    //    as liquid: one slow dome drifting across the face, far too broad to
    //    lattice, gently steering the interior lensing below.
    float s1 = sin(p.x * 0.010 + uTime * 0.55) * sin(p.y * 0.012 - uTime * 0.42);
    float s2 = sin((p.x - p.y) * 0.007 + uTime * 0.31);
    grad += 0.011 * uLiquid * float2(s1, s2) * a;

    // Press bulge: a soft dome that swells the glass under a pressed button. The
    // radial slope peaks mid-radius (derivative of a dome) so the surface reads as
    // a smooth bump that lenses the backdrop; uBulgeAmt animates it in/out.
    if (uBulgeAmt > 0.001) {
        float2 bc = uBulge * uSize;
        float R = (uBulgeR > 0.0) ? uBulgeR : (uSize.x / 6.0);
        float rr2 = distance(p, bc);
        float dome = smoothstep(R, 0.0, rr2);
        float2 bdir = (rr2 > 0.5) ? (p - bc) / rr2 : float2(0.0, 0.0);
        grad += bdir * (dome * (1.0 - dome)) * uBulgeAmt * 10.0;
    }

    // Lens rim, for a slab the caller says is a rounded rect filling the layer.
    // The alpha heightfield alone cannot make one: a solid fill steps from 0 to
    // 1 across its single anti-aliased pixel, so the bevel above is 2-4px wide
    // and everything inside it is dead flat — nothing for refract() to bend.
    // See LENS_RIM_SKSL for the profile.
    float2 lensSlope = (uLensW > 0.5)
        ? lensRimSlope(p, uSize, uLensR, uLensW) * uDepth
        : float2(0.0);

    // Surface normal from the alpha heightfield. Depth (profondeur) scales how
    // hard the bevel tips the normal off the surface — the dominant "3D" knob,
    // now a strong multiplier on the slope instead of a small z-base nudge.
    float slopeGain = 3.5 * uDepth;
    float3 N = normalize(float3(grad * slopeGain + lensSlope, 1.0));

    // Frost: per-pixel micro-roughness scatters the reflection, refraction and
    // glint into a misted, frosted surface. Gated so uFrost = 0 is unchanged.
    if (uFrost > 0.001) {
        float h1 = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        float h2 = fract(sin(dot(p, float2(39.346, 11.135))) * 24634.6345);
        N = normalize(N + float3((float2(h1, h2) - 0.5) * uFrost * 0.6, 0.0));
    }

    // The normal the LIGHT reads. Without a lens rim it is N. With one, the
    // rim's full slope is right for bending the backdrop but wrong for light:
    // the glint and the room's key light peak where the surface tilts ~15deg
    // toward them, which on the full rim is several dp inside the edge — a
    // second bright edge inside the alpha bevel's crisp outer line, so the
    // pane read as two layers depending on the light angle (seen on device).
    // Lighting a flatter copy of the rim (10% of its slope) moves that peak
    // to within ~1dp of the outer edge, where it joins the bevel line as one
    // edge.
    float3 NL = N;
    if (uLensW > 0.5) {
        NL = normalize(float3(grad * slopeGain + lensSlope * 0.1, 1.0));
        if (uFrost > 0.001) {
            float g1 = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
            float g2 = fract(sin(dot(p, float2(39.346, 11.135))) * 24634.6345);
            NL = normalize(NL + float3((float2(g1, g2) - 0.5) * uFrost * 0.6, 0.0));
        }
    }

    float2 uv = p / uSize;
    float3 I = float3(0.0, 0.0, -1.0);   // view ray, into the screen

    // Fresnel (Schlick, F0 = 0.04 for glass): ~4% reflection head-on, climbing
    // to ~100% at grazing edges. This is what makes the rim catch the light and
    // the face stay see-through — the core of the glass look. uFresnelPower sets
    // how broad the reflective rim band is (lower = wider shoulder).
    float cosV = clamp(NL.z, 0.0, 1.0);
    float fres = 0.04 + 0.96 * pow(1.0 - cosV, uFresnelPower);

    // Refraction (Snell, via refract) with a per-channel index of refraction so
    // R/G/B bend by different amounts — true chromatic dispersion, strongest at
    // the edges where the bevel turns. eta = n_air / n_glass ~ 0.66.
    float dispSpread = 0.06 * uDispersion;
    float3 Tr = refract(I, N, 0.66 - dispSpread);
    float3 Tg = refract(I, N, 0.66);
    float3 Tb = refract(I, N, 0.66 + dispSpread);
    // How far a bent ray travels, in uv. Without a lens rim it is a fixed share
    // of the pane, as it always was. With one it is measured in pixels against
    // the rim's own width — a fraction of the pane would push a long bar's
    // backdrop several times further sideways than up — at 5 rim widths per
    // unit of refraction, two at the 0.4 maximum. Keep the 5 equal to
    // LIVE_LENS_SRC's, so the slab and the live lens under it move together.
    float2 power = uRefraction *
        ((uLensW > 0.5) ? float2(uLensW * 5.0) / uSize : float2(1.6));

    // Interior slab parallax: a real glass pane offsets what's behind it even
    // where the surface is dead flat (thickness x viewing angle), which the
    // bevel-only refract() above can't produce — flat face ⇒ N = (0,0,1) ⇒ no
    // bend. Device tilt supplies the viewing angle and a slow drift keeps the
    // face alive when the phone is still; both scale with uRefraction so the
    // existing presets keep their meaning (refraction 0 stays perfectly flat).
    // The per-channel spread fringes the interior under high dispersion, so a
    // "prism" preset fringes the whole pane, not just its rim.
    float2 faceOfs = (uTilt * uTiltAmount * 0.35
                    + 0.05 * float2(sin(uTime * 0.23), cos(uTime * 0.19)) * uLiquid)
                    * uRefraction;
    float chroma = 0.30 * uDispersion;
    float2 uvR = uv + Tr.xy * power + faceOfs * (1.0 + chroma);
    float2 uvG = uv + Tg.xy * power + faceOfs;
    float2 uvB = uv + Tb.xy * power + faceOfs * (1.0 - chroma);
    float3 refr = float3(
        backdropTintAt(uvR).r * backdropField(uvR),
        backdropTintAt(uvG).g * backdropField(uvG),
        backdropTintAt(uvB).b * backdropField(uvB));

    // Real artwork, lensed at the SAME three displaced coordinates, so the
    // dispersion survives the swap: R, G and B each land on the pixel their own
    // index of refraction bends them to. At uArtMix = 0 the line above is
    // returned untouched — bit-identical to the reconstruction-only glass, which
    // is what lets this ship without re-tuning a single preset.
    if (uArtMix > 0.001) {
        refr = mix(refr, float3(artAt(uvR).r, artAt(uvG).g, artAt(uvB).b), uArtMix);
    }

    // Vibrancy: glass slightly saturates what shows through it (thin-slab
    // absorption). A restrained boost — enough to make the transmitted colour
    // read richer than the raw backdrop without posterizing dark tones.
    float lum = dot(refr, float3(0.299, 0.587, 0.114));
    refr = clamp(mix(float3(lum), refr, 1.22), 0.0, 1.0);

    // Reflected environment: the room the glass catches, turning with N so the
    // reflection streaks across the bevel as the surface curves. The key light
    // sits along uLightAngle; uTiltAmount scales how much device tilt sways it.
    float2 keyDir = float2(cos(uLightAngle), -sin(uLightAngle)) * 0.69;
    float3 refl = environment(reflect(I, NL), uTilt * uTiltAmount, keyDir, uTime, uLiquid);

    // Crisp specular glint from the same key light (uLightAngle + tilt), with a
    // uGloss-controlled exponent (higher = tighter mirror), dispersed for sparkle.
    float2 lightXY = float2(cos(uLightAngle), -sin(uLightAngle));
    float3 L = normalize(float3(
        // Scaled by uLiquid like every other time-varying term: the file's own
        // contract is that surfaceMotion = 0 "stays perfectly still", and these
        // three were the exceptions that kept the glint and the dispersion
        // drifting anyway — which meant a still preset still cost a frame clock.
        lightXY.x * 0.5 - uTilt.x * 0.8 * uTiltAmount + 0.25 * sin(uTime * 0.37) * uLiquid,
        lightXY.y * 0.5 + uTilt.y * 0.8 * uTiltAmount + 0.20 * cos(uTime * 0.29) * uLiquid,
        0.85));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));
    float ndh   = max(dot(NL, H), 0.0);
    float spec  = pow(ndh, uGloss);
    // The rainbow spread of the glint slowly widens and narrows, so the
    // chromatic fringe cycles instead of sitting frozen on the bevel.
    float dsp   = 0.015 * uDispersion * (1.0 + 0.35 * sin(uTime * 0.9) * uLiquid);
    float specR = pow(max(dot(normalize(NL + float3(dsp, 0.0, 0.0)), H), 0.0), uGloss);
    float specB = pow(max(dot(normalize(NL - float3(dsp, 0.0, 0.0)), H), 0.0), uGloss);

    // Edge twinkle: 4px cells pulse the glint with hash-staggered phases and
    // rates, so bevel highlights sparkle as points firing off one another
    // instead of glowing statically. Zero-mean-ish (baseline -0.15 against a
    // ~0.27 mean pulse) and scaled by uLiquid, so calm presets keep a steady
    // glint and the average brightness barely moves.
    float twHash = fract(sin(dot(floor(p * 0.25), float2(127.1, 311.7))) * 43758.5453);
    float twinkle = pow(0.5 + 0.5 * sin(uTime * (1.5 + 3.0 * twHash) + twHash * 6.2831), 4.0);
    float glintGain = 1.0 + (0.6 * twinkle - 0.15) * uLiquid;

    // The god rays' light on the glass. In front of the letters it lights the
    // bevels that face it — brighter than the flat face by however much more
    // they turn toward it — and throws a glint off them. Behind them (the
    // backlight) it shines through instead: the rims facing it glow, the way
    // the edge of a glass catches a light behind it. It only ever ADDS light:
    // darkening the bevels turned away, and the whole letter under a
    // backlight, read on device as shadows on the letters, and the lyrics'
    // shadow belongs on the background (lyricShadow).
    float3 rayAdd = float3(0.0);
    if (uRayAmount > 0.001) {
        float2 toRay = uRayLight.xy - p;
        float rd = length(toRay);
        float3 Lr = normalize(float3(toRay / max(uRayReach, 1.0), uRayLight.z));
        // Fades with distance from the light as the shafts do, never quite out.
        float k = uRayAmount * mix(0.35, 1.0, pow(uRayDecay, 50.0 * rd / max(uRayReach, 1.0)));
        float3 Hr = normalize(Lr + float3(0.0, 0.0, 1.0));
        float rayGlint = pow(max(dot(NL, Hr), 0.0), uGloss);
        if (uRayBack > 0.5) {
            float edgeness = length(N.xy);
            float2 out2 = (edgeness > 1e-4) ? N.xy / edgeness : float2(0.0);
            float toward = (rd > 0.5) ? max(dot(out2, toRay / rd), 0.0) : 1.0;
            rayAdd = uRayColor * k * (edgeness * (0.2 + toward) * 0.9 + rayGlint * 0.5);
        } else {
            // The bevels are 2-4px wide, so on its own their light reads as a
            // faint emboss; the face takes a share of it too (more the more
            // squarely the light falls on it), so the whole letter is seen
            // to be lit by the shafts' source.
            float facing = dot(N, Lr) - Lr.z;
            rayAdd = uRayColor * k * (max(facing, 0.0) * 2.4 + rayGlint * fres * 2.5 + Lr.z * 0.3);
        }
    }

    // Body: the glyph's own colour (kept legible) with a hint of the lensed
    // backdrop; leans more see-through over real blurred art.
    float3 glyphTint = float3(src.rgb) / a;
    float bodyMix = mix(0.72, 0.42, uBackdropMix);
    float3 bodyCol = mix(refr, glyphTint, bodyMix);
    // Over a live lens the real, bent backdrop is already underneath, so the
    // stand-in refraction here would only veil it with a smeared copy of the
    // cover. The body becomes a thin wash of plain tint (half the body
    // opacity, 10% at the default) and the rim, reflection and glint carry the
    // glass, as the demo's live-screen mode draws it.
    float bodyA = uBodyOpacity;
    if (uLiveUnder > 0.5) {
        bodyCol = glyphTint;
        bodyA = uBodyOpacity * 0.5;
    }

    // Fresnel-blend the reflection over the body (edges reflect, the face
    // transmits), then add the dispersed glint. uRimGain scales both the
    // reflection and the glint, so "Edge highlight" is a real brightness knob.
    // The glint is Fresnel-weighted too, so it rides the bevel edge rather than
    // washing the whole flat face white (a front-facing flat surface would
    // otherwise fire the specular uniformly).
    float3 col3 = mix(bodyCol, refl * uReflection, clamp(fres * 1.1, 0.0, 1.0));
    col3 += float3(specR, spec, specB) * uRimGain * fres * glintGain;
    col3 += rayAdd;

    // There is deliberately no traveling light sheet here. A soft diagonal band
    // used to glide across every pane every ~7s — the classic "shine" pass — and
    // it was the one motion here you could not look away from: it crosses the
    // whole pane, it is the brightest thing on it while it does, and unlike the
    // shimmer and the swell it has somewhere to be, so the eye tracks it off the
    // edge and then waits for the next one. Removed rather than damped: at any
    // brightness a band crossing the pane still reads as an event, and glass is
    // meant to be a surface, not a signal. The other liquid terms stay — they
    // undulate in place, so they read as material rather than as something
    // happening. Do not reintroduce a sweep here without asking.

    // Highlight shoulder: every edge term above stacks additively in the same
    // 1-2px band (Fresnel rim + glint, each gained by its own knob), and
    // the premultiply clamp below used to flatten any overflow into a solid
    // max-brightness line — the over-sharpened halo look when several edge
    // settings run hot. Roll intensities above the knee off asymptotically
    // toward white instead; pixels below the knee are bit-identical, so calm
    // presets keep their exact look and only blown rim pixels are compressed.
    float3 overHi = max(col3 - 0.82, float3(0.0));
    col3 = min(col3, float3(0.82)) + 0.18 * (1.0 - exp(-overHi / 0.18));

    // Transparent face, opaque bright rim: alpha is low across the body (backdrop
    // reads through) and climbs to full where Fresnel and the glint peak, so the
    // rim highlight reads as a crisp glass edge rather than being clamped away.
    // Same shoulder as the colour: the outline saturates to opaque gradually
    // instead of snapping, so the rim doesn't etch a hard 1px contour.
    // A lit edge carries alpha too, or the premultiply clamp below would cut
    // the rays' light off wherever the body is see-through.
    float rimSum = fres * 1.2 + spec + dot(rayAdd, float3(0.3333));
    float rim = min(rimSum, 0.82) + 0.18 * (1.0 - exp(-max(rimSum - 0.82, 0.0) / 0.18));
    float outA = clamp(a * (bodyA + (1.0 - bodyA) * rim), 0.0, a);

    float3 col = col3 * outA;              // premultiplied
    col = min(col, float3(outA));          // keep rgb <= alpha (premult-valid)
    return half4(half3(col), half(outA));
}
"""
