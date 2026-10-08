package tf.monochrome.recorder.editor.model

/**
 * Undo/redo over immutable values.
 *
 * Discrete edits [push] a step. Continuous ones — a slider being dragged —
 * [replace] the present many times and [commit] once at the end, so the whole
 * drag is one undo step back to where it started, not a hundred tiny ones.
 */
data class History<T>(
    val present: T,
    private val past: List<T> = emptyList(),
    private val future: List<T> = emptyList(),
    /** Where an uncommitted run of [replace] calls began. */
    private val anchor: T? = null,
    private val limit: Int = 100,
) {
    val canUndo: Boolean get() = past.isNotEmpty() || (anchor != null && anchor != present)
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records [next] as a new step. A no-op edit is not a step. */
    fun push(next: T): History<T> {
        val h = commit()
        return if (next == h.present) h else h.copy(present = next, past = (h.past + h.present).takeLast(limit), future = emptyList())
    }

    /** Replaces the present without making a step yet. */
    fun replace(next: T): History<T> = copy(present = next, anchor = anchor ?: present)

    /** Ends a run of [replace] calls as one step. */
    fun commit(): History<T> {
        val a = anchor ?: return this
        return if (a == present) copy(anchor = null)
        else copy(past = (past + a).takeLast(limit), future = emptyList(), anchor = null)
    }

    fun undo(): History<T> {
        val h = commit()
        return if (h.past.isEmpty()) h
        else h.copy(present = h.past.last(), past = h.past.dropLast(1), future = listOf(h.present) + h.future)
    }

    fun redo(): History<T> {
        val h = commit()
        return if (h.future.isEmpty()) h
        else h.copy(present = h.future.first(), past = h.past + h.present, future = h.future.drop(1))
    }
}
