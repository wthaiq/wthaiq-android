package com.wthaiq.signing.internal

/**
 * A strict, small JSON reader for the bridge messages (objects, arrays, strings, numbers, booleans, null).
 * Pure Kotlin so the decoder runs in JVM unit tests; `org.json` is a stub there.
 *
 * Returns `null` for malformed input, input longer than [MAX_LENGTH] or nesting deeper than [MAX_DEPTH].
 */
internal object MiniJson {
    const val MAX_LENGTH: Int = 65_536
    const val MAX_DEPTH: Int = 8

    fun parse(text: String): Any? {
        if (text.length > MAX_LENGTH) return null
        return try {
            val reader = Reader(text)
            val value = reader.readValue(0)
            reader.skipWhitespace()
            if (reader.atEnd) value else null
        } catch (_: MalformedJson) {
            null
        }
    }

    private class MalformedJson : Exception()

    private class Reader(private val text: String) {
        private var index = 0
        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw MalformedJson()
            skipWhitespace()
            if (atEnd) throw MalformedJson()
            return when (text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) throw MalformedJson()
            index += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            index++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw MalformedJson()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw MalformedJson()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            index++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return list
            }
            while (true) {
                list.add(readValue(depth + 1))
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw MalformedJson()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd) throw MalformedJson()
                val char = text[index++]
                when {
                    char == '"' -> return builder.toString()
                    char == '\\' -> builder.append(readEscape())
                    char < ' ' -> throw MalformedJson()
                    else -> builder.append(char)
                }
            }
        }

        private fun readEscape(): Char {
            val escaped = when (val char = next()) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> null
            }
            return escaped ?: throw MalformedJson()
        }

        private fun readUnicodeEscape(): Char? {
            if (index + 4 > text.length) return null
            val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
            index += 4
            return code.toChar()
        }

        private fun readNumber(): Double {
            val start = index
            while (index < text.length && text[index] in "+-0123456789.eE") index++
            val token = text.substring(start, index)
            if (token.isEmpty() || !numberPattern.matches(token)) throw MalformedJson()
            return token.toDoubleOrNull() ?: throw MalformedJson()
        }

        private fun peek(): Char = if (atEnd) throw MalformedJson() else text[index]

        private fun next(): Char = if (atEnd) throw MalformedJson() else text[index++]

        private fun expect(char: Char) {
            if (next() != char) throw MalformedJson()
        }
    }

    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
