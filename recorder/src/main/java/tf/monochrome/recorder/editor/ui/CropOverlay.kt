package tf.monochrome.recorder.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.editor.model.CropBox
import tf.monochrome.recorder.editor.model.CropRect
import kotlin.math.min

/**
 * The crop box over the whole picture: drag a corner or an edge to resize,
 * the inside to move. [ratio] (in fractions, see [CropBox]) holds the box's
 * shape; null leaves it free. [onChange] gets every step of a drag and
 * [onDragEnd] its end, so a drag is one undo step.
 *
 * It must be laid out exactly over the picture: positions are read as
 * fractions of this composable's size.
 */
@Composable
fun CropOverlay(
    rect: CropRect,
    ratio: Float?,
    onChange: (CropRect) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(rect)
    val lock by rememberUpdatedState(ratio)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onDragEnd)
    var dragging by remember { mutableStateOf(false) }
    Canvas(
        modifier.pointerInput(Unit) {
            val reach = 28.dp.toPx()
            awaitEachGesture {
                // Hold on from the first touch, without waiting for touch slop,
                // so a handle starts moving with the finger.
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val handle = CropBox.handleAt(current, down.position.x / w, down.position.y / h, reach / w, reach / h)
                    ?: return@awaitEachGesture
                down.consume()
                val start = current
                var total = Offset.Zero
                dragging = true
                drag(down.id) { c ->
                    total += c.positionChange()
                    c.consume()
                    change(CropBox.drag(start, handle, total.x / w, total.y / h, lock))
                }
                dragging = false
                end()
            }
        },
    ) {
        val l = rect.left * size.width
        val t = rect.top * size.height
        val r = rect.right * size.width
        val b = rect.bottom * size.height
        val shade = Color.Black.copy(alpha = 0.6f)
        drawRect(shade, Offset.Zero, Size(size.width, t))
        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t))
        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
        if (dragging) thirds(l, t, r, b)
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
        handles(l, t, r, b)
    }
}

private fun DrawScope.thirds(l: Float, t: Float, r: Float, b: Float) {
    val line = Color.White.copy(alpha = 0.5f)
    val stroke = 1.dp.toPx()
    for (i in 1..2) {
        val x = l + (r - l) * i / 3
        val y = t + (b - t) * i / 3
        drawLine(line, Offset(x, t), Offset(x, b), stroke)
        drawLine(line, Offset(l, y), Offset(r, y), stroke)
    }
}

/** Thick L-shapes at the corners and bars at the middle of each edge. */
private fun DrawScope.handles(l: Float, t: Float, r: Float, b: Float) {
    val stroke = 4.dp.toPx()
    val arm = min(20.dp.toPx(), min(r - l, b - t) / 3)
    val bar = min(12.dp.toPx(), min(r - l, b - t) / 4)
    val half = stroke / 2
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), stroke)
    // Corners, drawn just outside the frame line so they do not hide the picture's edge.
    line(l - half, t - half, l + arm, t - half)
    line(l - half, t - half, l - half, t + arm)
    line(r + half, t - half, r - arm, t - half)
    line(r + half, t - half, r + half, t + arm)
    line(l - half, b + half, l + arm, b + half)
    line(l - half, b + half, l - half, b - arm)
    line(r + half, b + half, r - arm, b + half)
    line(r + half, b + half, r + half, b - arm)
    val cx = (l + r) / 2
    val cy = (t + b) / 2
    line(cx - bar, t - half, cx + bar, t - half)
    line(cx - bar, b + half, cx + bar, b + half)
    line(l - half, cy - bar, l - half, cy + bar)
    line(r + half, cy - bar, r + half, cy + bar)
}
