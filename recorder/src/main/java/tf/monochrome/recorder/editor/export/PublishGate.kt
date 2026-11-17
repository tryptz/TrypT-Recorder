package tf.monochrome.recorder.editor.export

/**
 * Settles, once, the race between saving a finished export and the user
 * cancelling it. Publishing makes the file visible in the gallery and cannot
 * be taken back quietly, so whichever comes first wins: a cancel before it
 * stops the save and the file is discarded; once publishing has begun the
 * cancel is refused and the export ends as done — the honest answer, since
 * the file is there. Saving and cancelling run on different threads.
 */
internal class PublishGate {
    private enum class State { OPEN, PUBLISHING, CANCELLED }

    private var state = State.OPEN

    /** True if the save may publish; after this, [cancel] is refused. False once cancelled. */
    @Synchronized
    fun beginPublish(): Boolean {
        if (state == State.CANCELLED) return false
        state = State.PUBLISHING
        return true
    }

    /** True if the cancel stops the save; false once publishing has begun, when it is ignored. */
    @Synchronized
    fun cancel(): Boolean {
        if (state == State.PUBLISHING) return false
        state = State.CANCELLED
        return true
    }
}
