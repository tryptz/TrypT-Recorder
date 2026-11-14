package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A crop as the user sees and drags it: a box over the picture *as shown* —
 * already turned and mirrored — in fractions of its width and height, from
 * the top-left corner.
 */
@Serializable
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/** What a touch on the crop box takes hold of. */
enum class CropHandle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    MOVE,
    LEFT(left = true),
    TOP(top = true),
    RIGHT(right = true),
    BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true),
    TOP_RIGHT(top = true, right = true),
    BOTTOM_LEFT(left = true, bottom = true),
    BOTTOM_RIGHT(right = true, bottom = true),
    ;

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

/**
 * The geometry of the touch crop tool.
 *
 * [CropInsets] keeps a crop in the source's own orientation, because that is
 * where the export applies it: Crop runs before the turn and the mirroring.
 * The user, though, drags a box over the picture as it will come out. Media3
 * mirrors first and then turns (ScaleAndRotateTransformation post-scales,
 * then post-rotates), so that is the order used here too.
 *
 * Ratios are of the box in these fractions — width over height of the
 * fractions, not of pixels; [normalizedRatio] converts.
 */
object CropBox {
    /** The smallest share of either side a crop keeps. */
    const val MIN = CropInsets.MIN_KEEP

    /** The crop of [canvas] as a box over the picture as shown. */
    fun shown(canvas: CanvasSettings): CropRect {
        val c = canvas.crop
        val (x1, y1) = toShown(c.left, c.top, canvas)
        val (x2, y2) = toShown(1 - c.right, 1 - c.bottom, canvas)
        return CropRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /** The insets that give [rect] over the picture as [canvas] shows it. */
    fun insets(rect: CropRect, canvas: CanvasSettings): CropInsets {
        val (u1, v1) = toSource(rect.left, rect.top, canvas)
        val (u2, v2) = toSource(rect.right, rect.bottom, canvas)
        return CropInsets(
            left = tidy(min(u1, u2)),
            top = tidy(min(v1, v2)),
            right = tidy(1 - max(u1, u2)),
            bottom = tidy(1 - max(v1, v2)),
        )
    }

    /** A width:height ratio in pixels, as a ratio of fractions of a picture [frameAspect] wide per unit high. */
    fun normalizedRatio(pixelRatio: Float, frameAspect: Float): Float = pixelRatio / frameAspect

    /**
     * Which part of [rect] a touch at ([x], [y]) takes hold of, within
     * [slopX] / [slopY] (a fingertip, in fractions): corners first, then
     * edges, then the inside to move it. Null if it misses.
     */
    fun handleAt(rect: CropRect, x: Float, y: Float, slopX: Float, slopY: Float): CropHandle? {
        val dl = abs(x - rect.left) / slopX
        val dr = abs(x - rect.right) / slopX
        val dt = abs(y - rect.top) / slopY
        val db = abs(y - rect.bottom) / slopY
        val corner = listOf(
            CropHandle.TOP_LEFT to max(dl, dt),
            CropHandle.TOP_RIGHT to max(dr, dt),
            CropHandle.BOTTOM_LEFT to max(dl, db),
            CropHandle.BOTTOM_RIGHT to max(dr, db),
        ).minBy { it.second }
        if (corner.second <= 1f) return corner.first
        val alongX = x >= rect.left - slopX && x <= rect.right + slopX
        val alongY = y >= rect.top - slopY && y <= rect.bottom + slopY
        val edge = listOfNotNull(
            (CropHandle.LEFT to dl).takeIf { alongY },
            (CropHandle.RIGHT to dr).takeIf { alongY },
            (CropHandle.TOP to dt).takeIf { alongX },
            (CropHandle.BOTTOM to db).takeIf { alongX },
        ).minByOrNull { it.second }
        if (edge != null && edge.second <= 1f) return edge.first
        if (x > rect.left && x < rect.right && y > rect.top && y < rect.bottom) return CropHandle.MOVE
        return null
    }

    /**
     * [start] with [handle] dragged by ([dx], [dy]) in fractions, kept inside
     * the picture and at least [MIN] a side. With a [ratio], the box keeps
     * it: a corner scales from the opposite corner, an edge resizes about the
     * box's middle. Measured from where the drag began, so a handle stays
     * under the finger even after being held at the picture's edge.
     */
    fun drag(start: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float? = null): CropRect = when {
        handle == CropHandle.MOVE -> {
            val x = (start.left + dx).coerceIn(0f, 1f - start.width)
            val y = (start.top + dy).coerceIn(0f, 1f - start.height)
            CropRect(x, y, x + start.width, y + start.height)
        }
        ratio == null -> {
            val l = if (handle.left) (start.left + dx).coerceIn(0f, start.right - MIN) else start.left
            val r = if (handle.right) (start.right + dx).coerceIn(l + MIN, 1f) else start.right
            val t = if (handle.top) (start.top + dy).coerceIn(0f, start.bottom - MIN) else start.top
            val b = if (handle.bottom) (start.bottom + dy).coerceIn(t + MIN, 1f) else start.bottom
            CropRect(l, t, r, b)
        }
        handle.isCorner -> dragCorner(start, handle, dx, dy, ratio)
        else -> dragEdge(start, handle, dx, dy, ratio)
    }

    private fun dragCorner(s: CropRect, h: CropHandle, dx: Float, dy: Float, ratio: Float): CropRect {
        // The corner opposite stays put; sx/sy point from it to the dragged one.
        val ax = if (h.right) s.left else s.right
        val ay = if (h.bottom) s.top else s.bottom
        val sx = if (h.right) 1f else -1f
        val sy = if (h.bottom) 1f else -1f
        val w = ((if (h.right) s.right else s.left) + dx - ax) * sx
        val hh = ((if (h.bottom) s.bottom else s.top) + dy - ay) * sy
        // The point on the box's diagonal nearest the finger.
        val wanted = (w * ratio * ratio + hh * ratio) / (ratio * ratio + 1)
        val room = min(if (h.right) 1 - ax else ax, (if (h.bottom) 1 - ay else ay) * ratio)
        val width = min(max(wanted, minWidth(ratio)), room)
        val height = width / ratio
        val l = if (h.right) ax else ax - width
        val t = if (h.bottom) ay else ay - height
        return CropRect(l, t, l + width, t + height)
    }

    private fun dragEdge(s: CropRect, h: CropHandle, dx: Float, dy: Float, ratio: Float): CropRect {
        if (h.left || h.right) {
            val wanted = if (h.right) s.right + dx - s.left else s.right - (s.left + dx)
            val room = min(if (h.right) 1 - s.left else s.right, ratio)
            val width = min(max(wanted, minWidth(ratio)), room)
            val height = width / ratio
            val l = if (h.right) s.left else s.right - width
            val t = ((s.top + s.bottom) / 2 - height / 2).coerceIn(0f, 1f - height)
            return CropRect(l, t, l + width, t + height)
        }
        val wanted = if (h.bottom) s.bottom + dy - s.top else s.bottom - (s.top + dy)
        val room = min(if (h.bottom) 1 - s.top else s.bottom, 1f / ratio)
        val height = min(max(wanted, minWidth(ratio) / ratio), room)
        val width = height * ratio
        val t = if (h.bottom) s.top else s.bottom - height
        val l = ((s.left + s.right) / 2 - width / 2).coerceIn(0f, 1f - width)
        return CropRect(l, t, l + width, t + height)
    }

    /** The narrowest box of [ratio] that is still at least [MIN] each way. */
    private fun minWidth(ratio: Float): Float = max(MIN, MIN * ratio)

    /** The largest box of [ratio] that fits the picture, centred where [rect] is. */
    fun fitRatio(rect: CropRect, ratio: Float): CropRect {
        var w = 1f
        var h = w / ratio
        if (h > 1f) {
            h = 1f
            w = ratio
        }
        val l = ((rect.left + rect.right) / 2 - w / 2).coerceIn(0f, 1f - w)
        val t = ((rect.top + rect.bottom) / 2 - h / 2).coerceIn(0f, 1f - h)
        return CropRect(l, t, l + w, t + h)
    }

    /** Source point (u, v) to where it shows: mirrored, then turned clockwise. */
    private fun toShown(u: Float, v: Float, canvas: CanvasSettings): Pair<Float, Float> {
        val fu = if (canvas.flipHorizontal) 1 - u else u
        val fv = if (canvas.flipVertical) 1 - v else v
        return when (quarterTurns(canvas)) {
            1 -> (1 - fv) to fu
            2 -> (1 - fu) to (1 - fv)
            3 -> fv to (1 - fu)
            else -> fu to fv
        }
    }

    private fun toSource(x: Float, y: Float, canvas: CanvasSettings): Pair<Float, Float> {
        val (fu, fv) = when (quarterTurns(canvas)) {
            1 -> y to (1 - x)
            2 -> (1 - x) to (1 - y)
            3 -> (1 - y) to x
            else -> x to y
        }
        return (if (canvas.flipHorizontal) 1 - fu else fu) to (if (canvas.flipVertical) 1 - fv else fv)
    }

    private fun quarterTurns(canvas: CanvasSettings): Int = (((canvas.rotation % 360) + 360) % 360) / 90

    /** Four decimals is a tenth of a pixel on a 1440p frame; it also keeps "no crop" exactly zero. */
    private fun tidy(v: Float): Float = ((v * 10_000).roundToInt() / 10_000f).coerceIn(0f, 1f)
}
