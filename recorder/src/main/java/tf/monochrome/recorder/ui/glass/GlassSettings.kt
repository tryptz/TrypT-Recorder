/*
 * The recorder's liquid glass, ported from Tryptify's so the two apps are one
 * material. Shader source and every number are Tryptify's, copied verbatim;
 * only what leans on Tryptify-only systems was taken out. Sources, under
 * tf.monochrome.android in Tryptify:
 *
 * - ui/player/LiquidGlass.kt      → LiquidGlass.kt (LIQUID_GLASS_SRC, LENS_RIM_SKSL,
 *                                   lensRimPx, the shared shader, the slab, the frost)
 * - ui/player/LiveGlassLens.kt    → LiveGlassLens.kt (the live lens, LIVE_LENS_SRC)
 * - ui/player/GlassBackdropArt.kt → LiquidGlass.kt (bindBackdropArt's no-art path, EMPTY_ART)
 * - ui/components/GlassPanel.kt   → GlassPanel.kt (GlassPanel, GlassMaterial, GlassPill)
 * - ui/components/GlassPress.kt   → GlassPress.kt (GlassButton, the press swell)
 * - ui/components/GlassInk.kt     → GlassInk.kt (text that reads on the glass)
 * - ui/theme (glassTint, contrastRatio, MonoDimens.radiusLg) → GlassInk.kt, GlassPanel.kt
 * - domain/model/PlayerGlassSettings.kt → this file (FLOAT, Tryptify's INITIAL)
 *
 * Left behind: the backdrop album art (the slab always lenses its own
 * reconstructed field, uArtMix 0), the music links, the god-ray light (every
 * pane takes setNoRayLight), the gravity tilt, the performance profiles
 * (here: the shader may run, the backdrop may blur), the CRT effect's clock
 * stop, the lens divisor (always full resolution) and Tryptify's debug log.
 */
package tf.monochrome.recorder.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The glass material: the fields of Tryptify's `PlayerGlassSettings` that the
 * slab's shader, the frost and the live lens actually read, with the same
 * meaning. The constructor's defaults are [FLOAT], so `GlassSettings()` is the
 * same glass as Tryptify's out of the box.
 *
 * Not carried over: the drop-shadow fields and the Studio preview colour
 * (nothing here draws them), and tilt reactivity — the recorder has no gravity
 * sensor behind its glass, so the shader is always handed no tilt, and FLOAT's
 * reactivity is zero anyway.
 */
@Immutable
data class GlassSettings(
    /** Master on/off: off, every pane is the Material 3 fallback surface. */
    val enabled: Boolean = true,
    /** Body see-through amount (lower = more transparent). */
    val bodyOpacity: Float = 0.48956832f,
    /** How hard the bevel lenses the backdrop. */
    val refraction: Float = 0.25042957f,
    /** Specular rim brightness (the lit glass edge). */
    val rimBrightness: Float = 1.2720146f,
    /** Chromatic aberration at the refracting edges. */
    val dispersion: Float = 1.0006076f,
    /** Bevel sample rings 1/2/3 → 5/9/13 taps per pixel (quality vs GPU cost). */
    val sampleRings: Int = 3,
    /** Bevel shoulder width (1 = neutral, higher = rounder, softer glass edge). */
    val roundness: Float = 1.9778082f,
    /** Profondeur / relief: 1 = neutral, higher = steeper, deeper 3D bevel. */
    val depth: Float = 0.99957055f,
    /** Environment ("room") reflection strength on the glass (0 = none, 2 = strong). */
    val reflection: Float = 1.0004491f,
    /** Highlight polish: 0 = soft/frosted-wide glint, 1 = tight mirror-polished. */
    val gloss: Float = 1f,
    /**
     * Living-liquid surface motion: 0 = still glass, 1 = full shimmer/undulation.
     * Not free: above zero every pane runs a frame clock (see rememberFrameSeconds).
     */
    val surfaceMotion: Float = 0f,
    /** Key-light direction in degrees (0..360) — where the highlights sit. */
    val lightAngleDeg: Float = 112.78995f,
    /** Reflective rim width: 0 = thin crisp edge, 1 = broad glassy shoulder. */
    val edgeWidth: Float = 0.9997863f,
    /** Frosted roughness: 0 = clear glass, 1 = misted/frosted. */
    val frost: Float = 0f,
    /** Glass tint as an ARGB int; 0 = the theme's primary colour. */
    val tintColor: Int = 0,
    /**
     * Backdrop (Haze) frost blur radius in dp for panes that gaussian-blur
     * what's behind them. 0 disables the frost layer entirely. Distinct from
     * [frost], which is the shader's surface roughness. The live lens takes a
     * fifth of it (see LIVE_LENS_BLUR_SHARE).
     */
    val hazeBlurDp: Float = 31.726803f,
    /** Strength multiplier on the frost layer's luminance-picked tint (0–2). */
    val hazeTint: Float = 0f,
) {
    companion object {
        /**
         * Float — Tryptify's first glass theme and what every one of its glass
         * settings starts from, tuned on device. Half a body of glass with a
         * broad mirror shoulder. Still until touched: no surface motion and no
         * tilt, so no frame clock.
         */
        val FLOAT: GlassSettings = GlassSettings()

        /** What a pane draws with when nobody says otherwise: [FLOAT]. */
        val DEFAULT: GlassSettings get() = FLOAT
    }
}

/** The glass material for this subtree; [GlassSettings.FLOAT] unless a screen provides another. */
val LocalGlass: ProvidableCompositionLocal<GlassSettings> = compositionLocalOf { GlassSettings.FLOAT }

/**
 * The backdrop a screen's panes frost and bend: a haze source the screen draws
 * *beside* its glass, never one its glass is drawn inside (that paints the
 * source's flat base colour, the solid-slab failure). Null by default, so a
 * pane on a screen that provides none is the shader glass with no frost under
 * it rather than a broken blur.
 */
val LocalGlassBackdrop: ProvidableCompositionLocal<HazeState?> = compositionLocalOf { null }

/** A HazeState for a screen's backdrop. */
@Composable
fun rememberGlassBackdrop(): HazeState = rememberHazeState()

/**
 * Marks content as the backdrop panes frost/bend (Haze source). Panes must be
 * SIBLINGS of it, never inside it.
 *
 * A haze effect cannot sample a layer it is drawn inside: the frost then
 * paints the source's flat base colour instead of a blur, and the live lens,
 * drawing its own source's layer from inside itself, would recurse.
 */
fun Modifier.glassBackdrop(state: HazeState): Modifier = hazeSource(state)
