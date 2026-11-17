package tf.monochrome.recorder.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.CubeLut
import tf.monochrome.recorder.media.CreativeAssets

/**
 * The app ships its looks as compact .lut files made by
 * `tools/lut_to_binary.py` from the .cube sources in `tools/luts`. Each must
 * be exactly what parsing its .cube gives — name, size and every colour —
 * or a look would change by being converted.
 */
class CubeLutAssetsTest {
    /** Unit tests run from the module; from the repository root as well, to be safe. */
    private fun file(fromModule: String, fromRoot: String): File =
        File(fromModule).takeIf { it.exists() } ?: File(fromRoot)

    @Test
    fun `every bundled look reads the same from its lut as from its cube`() {
        assertEquals(8, CreativeAssets.luts.size)
        for (look in CreativeAssets.luts) {
            val cube = file("../tools/luts/${look.file}.cube", "tools/luts/${look.file}.cube")
            val lut = file(
                "src/main/assets/creative/luts/${look.file}.lut",
                "recorder/src/main/assets/creative/luts/${look.file}.lut",
            )
            assertTrue("${cube.path} is missing", cube.exists())
            assertTrue("${lut.path} is missing", lut.exists())
            val parsed = CubeLut.parse(cube.readText(), look.title)
            val read = CubeLut.readBinary(lut.readBytes(), look.title)
            assertEquals(look.file, parsed, read)
            assertEquals(look.title, read.name)
            assertEquals(17, read.size)
            assertTrue(parsed.colors.toIntArray().contentEquals(read.colors.toIntArray()))
            // And so the same cube at every mix.
            for (mix in listOf(0.35f, 1f)) {
                assertTrue(parsed.toRgbCube(mix).contentDeepEquals(read.toRgbCube(mix)))
            }
            // The licence line travels in the file.
            val notice = String(lut.readBytes(), Charsets.UTF_8)
            assertTrue(look.file, notice.contains("CC0") || notice.contains("MIT"))
        }
    }

    @Test
    fun `no bundled look is left as a cube in the app`() {
        val dir = file("src/main/assets/creative/luts", "recorder/src/main/assets/creative/luts")
        assertEquals(emptyList<String>(), dir.list().orEmpty().filter { it.endsWith(".cube") })
    }
}
