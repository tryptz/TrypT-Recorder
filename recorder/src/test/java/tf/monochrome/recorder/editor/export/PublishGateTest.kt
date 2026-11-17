package tf.monochrome.recorder.editor.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishGateTest {
    @Test
    fun `a cancel before publishing stops the save`() {
        val gate = PublishGate()
        assertTrue(gate.cancel())
        assertFalse(gate.beginPublish())
        // Asking again changes nothing.
        assertTrue(gate.cancel())
        assertFalse(gate.beginPublish())
    }

    @Test
    fun `once publishing has begun a cancel is refused, so the export ends as done`() {
        val gate = PublishGate()
        assertTrue(gate.beginPublish())
        assertFalse(gate.cancel())
        assertFalse(gate.cancel())
    }

    @Test
    fun `exactly one side wins when both race`() {
        repeat(200) {
            val gate = PublishGate()
            var published = false
            var cancelled = false
            val saver = Thread { published = gate.beginPublish() }
            val user = Thread { cancelled = gate.cancel() }
            saver.start(); user.start()
            saver.join(); user.join()
            assertTrue(published != cancelled)
        }
    }
}
