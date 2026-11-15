package tf.monochrome.recorder.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The record button's own colour, whatever the theme: red means recording everywhere. */
val RecordRed = Color(0xFFE5383B)
val DetachedTeal = Color(0xFF2EC4B6)
val MusicViolet = Color(0xFF9D4EDD)
val TextAmber = Color(0xFFFFB703)

/**
 * Material You's colours (Android 12+) on the recorder's dark ground. The
 * accent, its containers and the text tones come from the wallpaper; the
 * background and surfaces stay near-black, because the editor puts video on
 * them and glass over them, and a tinted mid-grey page reads as neither.
 */
private fun onDarkGround(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = Ground,
    surface = Ground,
    surfaceDim = Ground,
    surfaceContainerLowest = Color(0xFF080808),
    surfaceContainerLow = Color(0xFF101011),
    surfaceContainer = Color(0xFF161618),
    surfaceContainerHigh = Color(0xFF1E1E21),
    surfaceContainerHighest = Color(0xFF28282B),
)

private val Ground = Color(0xFF0B0B0C)

/**
 * Below Android 12 there is no wallpaper palette, so the accent is a fixed
 * amber — the editor's highlight colour — with matching Material roles.
 */
private val Fallback = onDarkGround(
    darkColorScheme(
        primary = Color(0xFFF2B33D),
        onPrimary = Color(0xFF2A1C00),
        primaryContainer = Color(0xFF5C4300),
        onPrimaryContainer = Color(0xFFFFDEA6),
        secondary = Color(0xFFDDC2A1),
        onSecondary = Color(0xFF3E2D16),
        secondaryContainer = Color(0xFF56442A),
        onSecondaryContainer = Color(0xFFFADEBC),
        tertiary = Color(0xFFB8CEA1),
        onTertiary = Color(0xFF243515),
        onBackground = Color(0xFFEDE1D4),
        onSurface = Color(0xFFEDE1D4),
        surfaceVariant = Color(0xFF4F4539),
        onSurfaceVariant = Color(0xFFD3C4B4),
        outline = Color(0xFF9C8F80),
        outlineVariant = Color(0xFF4F4539),
    ),
)

@Composable
fun RecorderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) onDarkGround(dynamicDarkColorScheme(context)) else Fallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
