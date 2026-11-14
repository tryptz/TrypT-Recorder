package tf.monochrome.recorder.editor.model

import java.util.Locale

object Captions {
    private val timestamp = Regex("""(\d{1,4}):(\d{2}):(\d{2})[,.](\d{3})""")

    fun parse(text: String): List<TextItem> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(Regex("\n[ \t]*\n+")).map { block ->
            val lines = block.lines()
            val index = if (lines.first().trim().all(Char::isDigit)) 1 else 0
            val timing = lines.getOrNull(index)?.split("-->", limit = 2) ?: error("Missing caption time")
            require(timing.size == 2)
            val start = parseTime(timing[0].trim())
            val end = parseTime(timing[1].trim().substringBefore(' '))
            val body = lines.drop(index + 1).joinToString("\n").trim()
            require(end > start && body.isNotEmpty())
            TextItem(newId(), body, start, end, caption = true)
        }.sortedBy { it.startUs }
    }

    fun write(items: List<TextItem>, durationUs: Long): String = items.asSequence()
        .filter { it.caption && it.text.isNotBlank() }
        .sortedBy { it.startUs }
        .mapNotNull { item ->
            val start = item.startUs.coerceIn(0, durationUs) / 1000
            val end = item.endUs.coerceIn(0, durationUs) / 1000
            if (end <= start) null else Triple(start, end, item.text.trim().replace(Regex("\n[ \t]*\n+"), "\n"))
        }.mapIndexed { index, (start, end, text) ->
            "${index + 1}\n${formatTime(start)} --> ${formatTime(end)}\n$text\n"
        }.joinToString("\n")

    private fun parseTime(value: String): Long {
        val parts = timestamp.matchEntire(value)?.groupValues ?: error("Invalid caption time")
        val (hours, minutes, seconds, millis) = parts.drop(1).map(String::toLong)
        require(minutes < 60 && seconds < 60)
        return (((hours * 60 + minutes) * 60 + seconds) * 1000 + millis) * 1000
    }

    private fun formatTime(ms: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000,
    )
}
