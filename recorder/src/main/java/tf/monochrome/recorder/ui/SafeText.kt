package tf.monochrome.recorder.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow

/**
 * [drawText] for a label placed at [topLeft] on a canvas. Compose lays the text
 * out in the space from [topLeft] to the canvas's far edges and throws when that
 * space is negative — a label placed at or past the right edge, which a timeline
 * ruler reaches whenever a tick lands at the very end (trimming moves the end
 * continuously). Here a label with no room is skipped, and one with little room
 * is cut off, instead of closing the app.
 */
fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    val room = labelRoom(size, topLeft) ?: return
    drawText(
        measurer, text, topLeft, style,
        overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
        size = room,
    )
}

/**
 * The space a label at [topLeft] has on a canvas of [canvas] size, or null when
 * it has less than a pixel either way (or starts off the canvas).
 */
fun labelRoom(canvas: Size, topLeft: Offset): Size? {
    val width = canvas.width - topLeft.x
    val height = canvas.height - topLeft.y
    if (!(width >= 1f && height >= 1f) || topLeft.x < 0f || topLeft.y < 0f) return null
    return Size(width, height)
}
