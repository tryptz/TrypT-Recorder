package tf.monochrome.recorder.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Compose's drawText lays a label out in the space from its top-left to the
 * canvas's far edges and throws ("maxWidth must be >= than minWidth") when
 * that space is negative. The timeline ruler put a label 4 px right of every
 * tick, so a tick in the last 4 px — which trimming reaches as it moves the
 * end — crashed the app. labelRoom decides what drawLabel gives drawText.
 */
class LabelRoomTest {
    private val canvas = Size(1000f, 18f)

    @Test
    fun `a label with room gets the space to the far edges`() {
        assertEquals(Size(996f, 18f), labelRoom(canvas, Offset(4f, 0f)))
    }

    @Test
    fun `a tick at the very end of the ruler is skipped, not a crash`() {
        // The reported crash: the last tick at x = width, its label at x + 4.
        assertNull(labelRoom(canvas, Offset(1004f, 0f)))
        // A tick 2 px from the end: drawText would have got a max width of -2.
        assertNull(labelRoom(canvas, Offset(1002f, 0f)))
        assertNull(labelRoom(canvas, Offset(999.5f, 0f)))
    }

    @Test
    fun `a label below or left of the canvas is skipped`() {
        assertNull(labelRoom(canvas, Offset(10f, 18f)))
        assertNull(labelRoom(canvas, Offset(-5f, 0f)))
        assertNull(labelRoom(Size(0f, 0f), Offset(0f, 0f)))
        assertNull(labelRoom(Size(Float.NaN, 18f), Offset(0f, 0f)))
    }
}
