package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.SnapinType
import tf.monochrome.recorder.editor.model.getParamDefs
import tf.monochrome.recorder.editor.model.shownParams
import tf.monochrome.recorder.editor.ui.fx.FxPresets
import java.io.File

/**
 * The editor sends each effect's parameters to the native mixer by index, and
 * projects save them by index too. Nothing at run time checks that the two
 * sides agree: a Kotlin list one entry short silently shifts every knob after
 * it. This reads the native headers and holds them together.
 */
class FxParametersNativeTest {
    private val dsp = File("src/main/cpp/tryptify-audio-core/dsp")

    /** The names in the first `enum Params { … }` (or `enum class SnapinType`) of [header]. */
    private fun enumNames(header: String, opening: Regex): List<String> {
        val start = opening.find(header) ?: error("no enum in header")
        val body = header.substring(start.range.last + 1, header.indexOf('}', start.range.last))
        return body.lines()
            .map { it.substringBefore("//") }
            .joinToString(" ")
            .split(',')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
    }

    private fun nativeParams(type: SnapinType): List<String> {
        val header = File(dsp, "snapins/${type.name.lowercase()}.h").readText()
        if (type == SnapinType.EQ_10BAND) {
            // A computed count: PREAMP, then NUM_BANDS × PARAMS_PER_BAND.
            val bands = Regex("""NUM_BANDS\s*=\s*(\d+)""").find(header)!!.groupValues[1].toInt()
            val perBand = Regex("""PARAMS_PER_BAND\s*=\s*(\d+)""").find(header)!!.groupValues[1].toInt()
            return List(1 + bands * perBand) { "P$it" }
        }
        val names = enumNames(header, Regex("""enum\s+Params\s*\{"""))
        return names.subList(0, names.indexOf("NUM_PARAMS"))
    }

    @Test
    fun `the effect list is the native SnapinType enum, in order`() {
        val header = File(dsp, "snapin_processor.h").readText()
        val native = enumNames(header, Regex("""enum\s+class\s+SnapinType[^{]*\{""")).dropLast(1)  // COUNT
        assertEquals(native, SnapinType.entries.map { it.name })
    }

    @Test
    fun `every effect has exactly the native parameters, reserved where native reserves`() {
        for (type in SnapinType.entries) {
            val native = nativeParams(type)
            val defs = getParamDefs(type)
            assertEquals("$type parameter count", native.size, defs.size)
            defs.forEachIndexed { i, def ->
                if (type != SnapinType.EQ_10BAND) {
                    assertEquals("$type #$i (${native[i]}) reserved", native[i].startsWith("RESERVED_"), def.reserved)
                }
            }
        }
    }

    @Test
    fun `knobs that did nothing are kept as hidden slots, so saved indices still line up`() {
        val reserved = mapOf(
            SnapinType.GATE to 8,
            SnapinType.COMPACTOR to 7,
            SnapinType.TRANSIENT_SHAPER to 5,
            SnapinType.BITCRUSH to 3,
            SnapinType.REVERSER to 1,
        )
        for ((type, index) in reserved) {
            val defs = getParamDefs(type)
            assertTrue("$type #$index", defs[index].reserved)
            assertFalse("$type #$index is not shown", index in shownParams(defs))
            assertEquals(defs.size - 1, shownParams(defs).size)
        }
        // A Bitcrush saved before: DAC 50 %, Dither 30 %, Mix 80 %. Dither and
        // Mix keep their meaning; the DAC value is simply never used.
        val saved = MixerFx(SnapinType.BITCRUSH, values = mapOf(3 to 50f, 4 to 30f, 5 to 80f))
        val defs = getParamDefs(saved.type)
        assertEquals("Dither", defs[4].name)
        assertEquals(30f, saved.values[4])
        assertEquals("Mix", defs[5].name)
        assertEquals(80f, saved.values[5])
        // The same for a Reverser saved with Sync on: X-Fade and Mix stay put.
        val reverser = getParamDefs(SnapinType.REVERSER)
        assertEquals(listOf("Time", "X-Fade", "Mix"), shownParams(reverser).map { reverser[it].name })
    }

    @Test
    fun `the trance gate's tempo is a new last knob that defaults to the old fixed 120`() {
        val defs = getParamDefs(SnapinType.TRANCE_GATE)
        assertEquals("BPM", defs.last().name)
        assertEquals(8, defs.lastIndex)
        // An older project has no value at index 8, so it plays as before.
        val saved = MixerFx(SnapinType.TRANCE_GATE, values = mapOf(0 to 2f, 6 to 80f))
        assertEquals(120f, saved.values[8] ?: defs[8].default)
    }

    @Test
    fun `presets only set parameters that exist and are shown`() {
        for (type in SnapinType.entries) {
            val defs = getParamDefs(type)
            for (preset in FxPresets.forType(type)) {
                for (index in preset.values.keys) {
                    assertTrue("${type.name} '${preset.name}' #$index", index in defs.indices && !defs[index].reserved)
                }
            }
        }
    }
}
