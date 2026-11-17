package tf.monochrome.recorder.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonVisibilityTest {
    private val recording = CaptureStatus.Recording(startedAtMs = 0)

    @Test fun hiddenOnlyWhenAskedFor() {
        assertFalse(ButtonVisibility.hidden(false, true, recording))
        assertTrue(ButtonVisibility.hidden(true, false, recording))
        assertTrue(ButtonVisibility.hidden(true, false, CaptureStatus.Saving))
    }

    @Test fun hiddenWhileTheFirstFrameIsBeingCaptured() {
        // No countdown: the status is still Idle when the button goes.
        assertTrue(ButtonVisibility.hidden(true, true, CaptureStatus.Idle))
    }

    @Test fun aStartThatFailedOrWasCancelledShowsTheButtonAgain() {
        assertFalse(ButtonVisibility.hidden(true, false, CaptureStatus.Idle))
        assertFalse(ButtonVisibility.hidden(true, false, CaptureStatus.Countdown(3)))
    }

    @Test fun storageMargin() {
        assertTrue(StorageGuard.isLow(StorageGuard.MARGIN_BYTES - 1))
        assertFalse(StorageGuard.isLow(StorageGuard.MARGIN_BYTES))
        assertFalse("unknown is not low", StorageGuard.isLow(null))
    }
}
