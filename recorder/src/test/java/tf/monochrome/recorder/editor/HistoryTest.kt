package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.History

class HistoryTest {
    @Test
    fun `undo and redo walk the steps`() {
        val h = History(1).push(2).push(3)
        assertEquals(2, h.undo().present)
        assertEquals(1, h.undo().undo().present)
        assertEquals(3, h.undo().redo().present)
        assertFalse(History(1).canUndo)
    }

    @Test
    fun `a no-op edit is not a step`() {
        val h = History(1).push(1)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a new edit drops the redo stack`() {
        val h = History(1).push(2).undo().push(5)
        assertFalse(h.canRedo)
        assertEquals(1, h.undo().present)
    }

    @Test
    fun `a slider drag is one step back to where it began`() {
        val dragged = History(10).replace(11).replace(12).replace(13)
        assertTrue(dragged.canUndo)
        val committed = dragged.commit()
        assertEquals(13, committed.present)
        assertEquals(10, committed.undo().present)
        // An undo mid-drag commits first, then steps back.
        assertEquals(10, History(10).replace(15).undo().present)
        // A drag that ends where it began leaves no step.
        assertFalse(History(10).replace(12).replace(10).commit().canUndo)
    }

    @Test
    fun `history is bounded`() {
        var h = History(0, limit = 3)
        for (i in 1..10) h = h.push(i)
        h = h.undo().undo().undo()
        assertEquals(7, h.present)
        assertFalse(h.canUndo)
    }
}
