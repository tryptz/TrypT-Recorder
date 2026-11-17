package tf.monochrome.recorder.editor.store

import tf.monochrome.recorder.editor.model.CubeLut
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.SpeedKey

/**
 * What an imported bundle's project.json must hold before it becomes a draft.
 * A bundle is a file from anywhere: a speed of zero or NaN divides timeline
 * arithmetic into infinity, and a LUT whose colour list is not size³ indexes
 * past its end when the grade is built. Rejected here, the import fails with
 * a message instead of the editor crashing later. Pure, JVM-tested.
 */
object BundleValidation {
    /** Bundle paths: `media/` plus a six-digit name. The `media/` folder itself may be listed as an entry. */
    val mediaPath = Regex("""media/[0-9]{6}\.[a-z0-9]{1,8}""")
    const val MEDIA_DIRECTORY = "media/"

    fun problems(project: Project): List<String> = buildList {
        project.clips.forEach { clip ->
            speed("clip ${clip.id}", clip.speed, clip.speedKeys)
            clip.picture.grade.lut?.let { lut("clip ${clip.id}", it) }
        }
        project.audio.forEach { speed("audio ${it.id}", it.speed, it.speedKeys) }
    }

    fun requireValid(project: Project) {
        val found = problems(project)
        require(found.isEmpty()) { "invalid project: ${found.joinToString("; ")}" }
    }

    private fun MutableList<String>.speed(what: String, speed: Float, keys: List<SpeedKey>) {
        if (!(speed.isFinite() && speed > 0f)) add("$what speed $speed")
        keys.forEach { if (!(it.rate.isFinite() && it.rate > 0f)) add("$what speed key rate ${it.rate}") }
    }

    private fun MutableList<String>.lut(what: String, lut: CubeLut) {
        if (lut.size !in 2..MAX_LUT_SIZE) {
            add("$what LUT size ${lut.size}")
        } else if (lut.colors.size != lut.size * lut.size * lut.size) {
            add("$what LUT has ${lut.colors.size} colours for size ${lut.size}")
        }
    }

    /** As [CubeLut.parse] accepts. */
    private const val MAX_LUT_SIZE = 33
}
