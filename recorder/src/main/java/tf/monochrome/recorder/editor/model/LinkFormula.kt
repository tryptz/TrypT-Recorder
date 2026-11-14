package tf.monochrome.recorder.editor.model

/**
 * The music link's optional formula: a small arithmetic expression over
 * `value` (where the link would put the setting) and `band` (the band's
 * strength, 0..1; `fft[band]` is accepted too, as the spec writes it).
 *
 * It is never run as code. [compile] turns the text into a flat program of
 * fixed steps ([program]: pairs of op and argument, in reverse Polish order),
 * and [evaluate] walks it with a fixed-size stack. The native mixer walks the
 * same program on the audio thread (`mod_route.h`), which is why the encoding
 * is a plain float array: it crosses JNI as-is, and the audio thread neither
 * parses, allocates nor throws.
 */
object LinkFormula {
    const val MAX_LENGTH = 120
    const val MAX_STEPS = 64
    const val MAX_STACK = 16

    const val OP_CONST = 0f
    const val OP_VALUE = 1f
    const val OP_BAND = 2f
    const val OP_ADD = 3f
    const val OP_SUB = 4f
    const val OP_MUL = 5f
    const val OP_DIV = 6f
    const val OP_NEG = 7f
    const val OP_MIN = 8f
    const val OP_MAX = 9f
    const val OP_ABS = 10f
    const val OP_CLAMP = 11f

    /** What is wrong with a formula, for the editor to say in plain words. */
    enum class Problem {
        UNKNOWN_WORD,
        BAD_SYMBOL,
        MISSING_BRACKET,
        ENDS_WITH_OPERATOR,
        NO_BAND,
        TOO_LONG,
        NOT_FINISHED,
    }

    /**
     * A compiled formula. [program] is null for a blank formula (no formula,
     * which is valid) and for a broken one, which has a [problem] and, where
     * it helps, the word or symbol at fault in [detail].
     */
    data class Compiled(val program: FloatArray?, val problem: Problem? = null, val detail: String = "") {
        val ok: Boolean get() = problem == null

        override fun equals(other: Any?): Boolean =
            other is Compiled && problem == other.problem && detail == other.detail &&
                (program?.contentEquals(other.program) ?: (other.program == null))

        override fun hashCode(): Int = 31 * (program?.contentHashCode() ?: 0) + (problem?.hashCode() ?: 0)
    }

    private val FUNCTIONS = mapOf("min" to 2, "max" to 2, "abs" to 1, "clamp" to 3)
    private val WORDS = setOf("value", "band", "fft") + FUNCTIONS.keys

    fun compile(text: String): Compiled {
        val src = text.trim()
        if (src.isEmpty()) return Compiled(null)
        if (src.length > MAX_LENGTH) return Compiled(null, Problem.TOO_LONG)
        val tokens = tokenize(src) ?: return Compiled(null, Problem.BAD_SYMBOL, firstBadSymbol(src))
        tokens.firstOrNull { it.kind == Kind.WORD && it.text !in WORDS }?.let {
            return Compiled(null, Problem.UNKNOWN_WORD, it.text)
        }
        var depth = 0
        for (t in tokens) {
            if (t.text == "(") depth++
            if (t.text == ")") depth--
            if (depth < 0) return Compiled(null, Problem.MISSING_BRACKET)
        }
        if (depth != 0) return Compiled(null, Problem.MISSING_BRACKET)
        val last = tokens.last()
        if (last.kind == Kind.SYMBOL && last.text in setOf("+", "-", "*", "/", ",")) {
            return Compiled(null, Problem.ENDS_WITH_OPERATOR)
        }
        val parser = Parser(tokens)
        val ok = runCatching { parser.expression() }.isSuccess && parser.atEnd()
        if (!ok) return Compiled(null, Problem.NOT_FINISHED)
        if (parser.steps.size / 2 > MAX_STEPS) return Compiled(null, Problem.TOO_LONG)
        if (!parser.usesBand) return Compiled(null, Problem.NO_BAND)
        val program = parser.steps.toFloatArray()
        if (maxDepth(program) > MAX_STACK) return Compiled(null, Problem.TOO_LONG)
        return Compiled(program)
    }

    /** Runs [program] for one value; null if it cannot give a finite number. */
    fun evaluate(program: FloatArray, value: Float, band: Float): Float? {
        val stack = FloatArray(MAX_STACK)
        var sp = 0
        var i = 0
        while (i + 1 < program.size) {
            val op = program[i]
            val arg = program[i + 1]
            i += 2
            when (op) {
                OP_CONST -> { if (sp >= MAX_STACK) return null; stack[sp++] = arg }
                OP_VALUE -> { if (sp >= MAX_STACK) return null; stack[sp++] = value }
                OP_BAND -> { if (sp >= MAX_STACK) return null; stack[sp++] = band }
                OP_NEG -> { if (sp < 1) return null; stack[sp - 1] = -stack[sp - 1] }
                OP_ABS -> { if (sp < 1) return null; stack[sp - 1] = kotlin.math.abs(stack[sp - 1]) }
                OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX -> {
                    if (sp < 2) return null
                    val b = stack[--sp]
                    val a = stack[sp - 1]
                    stack[sp - 1] = when (op) {
                        OP_ADD -> a + b
                        OP_SUB -> a - b
                        OP_MUL -> a * b
                        OP_DIV -> if (b == 0f) 0f else a / b
                        OP_MIN -> minOf(a, b)
                        else -> maxOf(a, b)
                    }
                }
                OP_CLAMP -> {
                    if (sp < 3) return null
                    val hi = stack[--sp]
                    val lo = stack[--sp]
                    val x = stack[sp - 1]
                    stack[sp - 1] = x.coerceIn(minOf(lo, hi), maxOf(lo, hi))
                }
                else -> return null
            }
        }
        if (sp != 1) return null
        return stack[0].takeIf { it.isFinite() }
    }

    private fun maxDepth(program: FloatArray): Int {
        var sp = 0
        var peak = 0
        var i = 0
        while (i + 1 < program.size) {
            sp += when (program[i]) {
                OP_CONST, OP_VALUE, OP_BAND -> 1
                OP_NEG, OP_ABS -> 0
                OP_CLAMP -> -2
                else -> -1
            }
            peak = maxOf(peak, sp)
            i += 2
        }
        return peak
    }

    private enum class Kind { NUMBER, WORD, SYMBOL }

    private data class Token(val kind: Kind, val text: String)

    /** Typed and pasted variants of the operators the editor's hint shows. */
    private fun normalize(c: Char): Char = when (c) {
        '×', '·' -> '*'
        '÷' -> '/'
        '−', '–' -> '-'
        else -> c
    }

    private fun tokenize(src: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < src.length) {
            val c = normalize(src[i])
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val start = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    val text = src.substring(start, i)
                    if (text.count { it == '.' } > 1 || text.toFloatOrNull() == null) return null
                    out += Token(Kind.NUMBER, text)
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Token(Kind.WORD, src.substring(start, i).lowercase())
                }
                c in "+-*/(),[]" -> {
                    out += Token(Kind.SYMBOL, c.toString())
                    i++
                }
                else -> return null
            }
        }
        return out
    }

    private fun firstBadSymbol(src: String): String =
        src.firstOrNull { val c = normalize(it); !(c.isWhitespace() || c.isLetterOrDigit() || c == '_' || c == '.' || c in "+-*/(),[]") }
            ?.toString() ?: ""

    /** Recursive descent straight into reverse Polish steps. */
    private class Parser(private val tokens: List<Token>) {
        val steps = ArrayList<Float>()
        var usesBand = false
        private var pos = 0

        fun atEnd(): Boolean = pos >= tokens.size

        private fun peek(): Token? = tokens.getOrNull(pos)

        private fun take(): Token = tokens.getOrNull(pos++) ?: error("end")

        private fun expect(symbol: String) {
            val t = take()
            check(t.kind == Kind.SYMBOL && t.text == symbol)
        }

        private fun emit(op: Float, arg: Float = 0f) {
            steps += op
            steps += arg
        }

        fun expression() {
            term()
            while (true) {
                val t = peek() ?: return
                if (t.kind != Kind.SYMBOL || (t.text != "+" && t.text != "-")) return
                take()
                term()
                emit(if (t.text == "+") OP_ADD else OP_SUB)
            }
        }

        private fun term() {
            unary()
            while (true) {
                val t = peek() ?: return
                if (t.kind != Kind.SYMBOL || (t.text != "*" && t.text != "/")) return
                take()
                unary()
                emit(if (t.text == "*") OP_MUL else OP_DIV)
            }
        }

        private fun unary() {
            val t = peek()
            if (t != null && t.kind == Kind.SYMBOL && t.text == "-") {
                take()
                unary()
                emit(OP_NEG)
                return
            }
            primary()
        }

        private fun primary() {
            val t = take()
            when (t.kind) {
                Kind.NUMBER -> emit(OP_CONST, t.text.toFloat())
                Kind.SYMBOL -> {
                    check(t.text == "(")
                    expression()
                    expect(")")
                }
                Kind.WORD -> when (t.text) {
                    "value" -> emit(OP_VALUE)
                    "band" -> {
                        usesBand = true
                        emit(OP_BAND)
                    }
                    "fft" -> {
                        expect("[")
                        val inner = take()
                        check(inner.kind == Kind.WORD && inner.text == "band")
                        expect("]")
                        usesBand = true
                        emit(OP_BAND)
                    }
                    else -> {
                        val arity = FUNCTIONS[t.text] ?: error("word")
                        expect("(")
                        expression()
                        repeat(arity - 1) {
                            expect(",")
                            expression()
                        }
                        expect(")")
                        emit(
                            when (t.text) {
                                "min" -> OP_MIN
                                "max" -> OP_MAX
                                "abs" -> OP_ABS
                                else -> OP_CLAMP
                            },
                        )
                    }
                }
            }
        }
    }
}
