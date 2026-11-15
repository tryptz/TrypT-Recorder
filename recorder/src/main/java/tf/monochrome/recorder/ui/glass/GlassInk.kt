package tf.monochrome.recorder.ui.glass

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What the glass in this subtree lies over, when that is not the page — a
 * video preview, say, which is dark under every theme. Null means the theme's
 * own background, which is what an ordinary screen's panes frost.
 *
 * The frost washes toward it (dark over dark, light over light) and the text on
 * the glass is chosen against it ([GlassInkScope]). The theme alone cannot
 * say: a light theme's video is still dark, and its panes are dark glass.
 */
internal val LocalGlassGround = compositionLocalOf<Color?> { null }

/**
 * The accent the glass was tinted with before a pane moved it for its own
 * text. A pane inside a pane (a chip on a sheet) tints its slab with this, not
 * with the readable accent its parent handed down, so both stay one material.
 */
internal val LocalGlassAccent = compositionLocalOf<Color?> { null }

/**
 * The colour a pane of glass is tinted: [explicitArgb] when the material names
 * one, otherwise the accent — and inside a pane, the accent the glass had
 * before its text was moved, not the readable one: a chip on a sheet is tinted
 * as the sheet is.
 */
@Composable
internal fun glassTint(explicitArgb: Int): Color {
    if (explicitArgb != 0) return Color(explicitArgb)
    return LocalGlassAccent.current ?: MaterialTheme.colorScheme.primary
}

/** The text on one pane of glass: the words, the quieter words, and the accent. */
internal data class GlassInk(val text: Color, val muted: Color, val accent: Color)

/**
 * A pane of glass as one colour, as the eye averages it: the frost over the
 * [ground], and the slab's [tint] over that at the share of it the shader
 * lets through ([bodyOpacity] times its body mix). With the shader off it is
 * the plain [fallback] surface instead, at the opacity the fallback draws it.
 */
internal fun glassFace(
    ground: Color,
    frost: Color,
    tint: Color,
    bodyOpacity: Float,
    shaded: Boolean,
    fallback: Color,
): Color {
    val frosted = frost.compositeOver(ground)
    return if (shaded) {
        tint.copy(alpha = (bodyOpacity * BODY_MIX).coerceIn(0f, 1f)).compositeOver(frosted)
    } else {
        fallback.copy(alpha = bodyOpacity.coerceIn(FALLBACK_MIN_ALPHA, 1f)).compositeOver(frosted)
    }
}

/**
 * Light text on dark glass, dark text on light — whichever reads better on
 * [face], the way the themes choose ink per surface. The [accent] keeps its
 * hue and moves toward the text only as far as it must to read on the glass
 * (3:1, the floor for large and bold type, which is what an accent on glass
 * marks): a lime accent on lime glass would otherwise vanish into it.
 */
internal fun glassInk(face: Color, accent: Color): GlassInk {
    // Which way is decided by pure white against pure black, which between
    // them clear AA on any pane (their crossover is at about 4.58); the
    // softened ink is used when it still does, which is almost everywhere.
    val light = contrastRatio(Color.White, face) >= contrastRatio(Color.Black, face)
    val soft = if (light) LIGHT_INK else DARK_INK
    val text = if (contrastRatio(soft, face) >= TEXT_CONTRAST) soft else if (light) Color.White else Color.Black
    val muted = text.copy(alpha = MUTED_ALPHA)
    return GlassInk(text = text, muted = muted, accent = readableAccent(accent, face, text))
}

private fun readableAccent(accent: Color, face: Color, toward: Color): Color {
    val solid = accent.copy(alpha = 1f)
    if (contrastRatio(solid, face) >= ACCENT_CONTRAST) return accent
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (contrastRatio(lerp(solid, toward, mid), face) >= ACCENT_CONTRAST) high = mid else low = mid
    }
    return lerp(solid, toward, high).copy(alpha = accent.alpha)
}

/**
 * Gives [content] text that reads on the glass it sits on: the colour scheme's
 * text slots (onSurface, onSurfaceVariant, onBackground) and the content
 * colour become light on dark glass and dark on light glass, and the accent is
 * made readable on it. Glass is not the page, so the theme's own ink — dark in
 * a light theme — is the wrong answer on a dark pane, and the other way round.
 *
 * Used inside every pane ([GlassPanel], [GlassButton], [GlassPill]), so the
 * rule is in one place.
 */
@Composable
internal fun GlassInkScope(glass: GlassSettings, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = LocalGlassAccent.current ?: scheme.primary
    val ground = LocalGlassGround.current ?: scheme.background
    val tint = glassTint(glass.tintColor)
    val shaded = shaderGlassFor(glass) && rememberLiquidGlassAvailable(glass)
    val frost = glassFrostTint(glass, ground.luminance() <= 0.5f)
    val face = glassFace(ground, frost, tint, glass.bodyOpacity, shaded, scheme.surfaceContainerHigh)
    val ink = remember(face, scheme.primary) { glassInk(face, scheme.primary) }
    val onGlass = remember(scheme, ink) {
        scheme.copy(
            onSurface = ink.text,
            onSurfaceVariant = ink.muted,
            onBackground = ink.text,
            primary = ink.accent,
        )
    }
    // MaterialTheme resets the text style to bodyLarge; whatever the caller
    // had set for this content is put back inside it.
    val style = LocalTextStyle.current
    CompositionLocalProvider(LocalGlassAccent provides accent) {
        MaterialTheme(colorScheme = onGlass, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(
                LocalContentColor provides ink.text,
                LocalTextStyle provides style,
                content = content,
            )
        }
    }
}

/** Near-white and near-black: pure ones glare on glass, and these still clear AA where they are chosen. */
internal val LIGHT_INK = Color(0xFFF5F5F0)
internal val DARK_INK = Color(0xFF15161A)

/** The quieter text: captions, values. */
private const val MUTED_ALPHA = 0.76f

/** How much of the tint the shader's body shows over the backdrop it lenses (its body mix, 0.42..0.72). */
private const val BODY_MIX = 0.6f

private const val ACCENT_CONTRAST = 3.0
private const val TEXT_CONTRAST = 4.5

/**
 * A switch on glass, in the glass's own ink. Material's switch takes its "off"
 * track and thumb from the theme's surface and outline, which a pane's ink
 * does not move, so in a light theme an off switch was a white pill on dark
 * glass. Inside [GlassInkScope] `onSurface` is the glass's ink, and "off" is
 * drawn from it: dark on light glass, light on dark.
 */
@Composable
internal fun glassSwitchColors(
    accent: Color = MaterialTheme.colorScheme.primary,
    checkedThumb: Color = if (accent.luminance() > 0.5f) DARK_INK else LIGHT_INK,
): SwitchColors {
    val ink = MaterialTheme.colorScheme.onSurface
    return SwitchDefaults.colors(
        checkedTrackColor = accent,
        checkedThumbColor = checkedThumb,
        checkedBorderColor = accent,
        uncheckedTrackColor = ink.copy(alpha = SWITCH_OFF_TRACK),
        uncheckedThumbColor = ink.copy(alpha = SWITCH_OFF_THUMB),
        uncheckedBorderColor = ink.copy(alpha = SWITCH_OFF_BORDER),
    )
}

/** An off switch on glass: a faint track, a quieter thumb, and a rim between them. */
private const val SWITCH_OFF_TRACK = 0.12f
private const val SWITCH_OFF_THUMB = 0.6f
private const val SWITCH_OFF_BORDER = 0.35f

/** WCAG relative luminance of [color], ignoring alpha. */
internal fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
