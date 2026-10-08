package tf.monochrome.recorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RecordRed = Color(0xFFE5383B)
val DetachedTeal = Color(0xFF2EC4B6)
val MusicViolet = Color(0xFF9D4EDD)
val TextAmber = Color(0xFFFFB703)

/** Tryptify's monochrome — black ground, white type — with one accent: record red. */
private val Scheme = darkColorScheme(
    primary = RecordRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0D0E),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF0C0C0C),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF2E2E2E),
)

@Composable
fun RecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
