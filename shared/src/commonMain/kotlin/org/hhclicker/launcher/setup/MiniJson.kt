package org.hhclicker.launcher.setup

/**
 * Parsed JSON value.
 *
 * Docker settings files are read as JSON, never with a regular expression: a key such as
 * `wslEngineEnabled` may appear inside a comment-like string, in a nested object or with unusual
 * whitespace, and a textual match would answer the wrong question.
 */
sealed interface JsonValue {
    data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue
    data class JsonArray(val items: List<JsonValue>) : JsonValue
    data class JsonString(val value: String) : JsonValue
    /** Original JSON representation is retained so rewriting a document cannot change integer precision. */
    data class JsonNumber(val value: String) : JsonValue
    data class JsonBoolean(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
}

/**
 * Small strict recursive descent JSON parser.
 *
 * The launcher needs to read two small Docker configuration files and nothing else, so it carries its own
 * parser instead of a serialization dependency. It is pure, has no platform calls and is unit tested.
 */
object MiniJson {

    /** Returns the parsed document, or null when the text is absent or not valid JSON. */
    fun parseOrNull(text: String?): JsonValue? {
        if (text.isNullOrBlank()) return null
        return try {
            val parser = Parser(text)
            val value = parser.readValue()
            parser.skipWhitespace()
            if (!parser.exhausted()) null else value
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    /** Reads a top level boolean field, or null when it is missing or is not a boolean. */
    fun booleanField(document: JsonValue?, name: String): Boolean? {
        val entry = (document as? JsonValue.JsonObject)?.entries?.get(name)
        return (entry as? JsonValue.JsonBoolean)?.value
    }

    /** Serializes a parsed value back to strict JSON without losing unknown fields. */
    fun stringify(value: JsonValue): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: JsonValue) {
        when (value) {
            is JsonValue.JsonObject -> {
                append('{')
                value.entries.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key)
                    append(':')
                    appendJson(entry.value)
                }
                append('}')
            }
            is JsonValue.JsonArray -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }
            is JsonValue.JsonString -> appendQuoted(value.value)
            is JsonValue.JsonNumber -> append(value.value)
            is JsonValue.JsonBoolean -> append(value.value)
            JsonValue.JsonNull -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class JsonSyntaxException : Exception()

    private class Parser(private val source: String) {
        private var index = 0

        fun exhausted(): Boolean = index >= source.length

        fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (exhausted()) fail()
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.JsonString(readString())
                't' -> readLiteral("true", JsonValue.JsonBoolean(true))
                'f' -> readLiteral("false", JsonValue.JsonBoolean(false))
                'n' -> readLiteral("null", JsonValue.JsonNull)
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.JsonObject(entries)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                entries[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.JsonObject(entries)
                    }
                    else -> fail()
                }
            }
        }

        private fun readArray(): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.JsonArray(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.JsonArray(items)
                    }
                    else -> fail()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (exhausted()) fail()
                when (val character = source[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> builder.append(character)
                }
            }
        }

        private fun readEscape(): Char {
            if (exhausted()) fail()
            return when (val marker = source[index++]) {
                '"', '\\', '/' -> marker
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > source.length) fail()
                    val code = source.substring(index, index + 4).toIntOrNull(16) ?: fail()
                    index += 4
                    code.toChar()
                }
                else -> fail()
            }
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (peek() == '-' || peek() == '+') index++
            while (!exhausted() && (source[index].isDigit() || source[index] in ".eE+-")) index++
            val rawValue = source.substring(start, index)
            rawValue.toDoubleOrNull() ?: fail()
            return JsonValue.JsonNumber(rawValue)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!source.startsWith(literal, index)) fail()
            index += literal.length
            return value
        }

        private fun peek(): Char? = if (exhausted()) null else source[index]

        private fun expect(character: Char) {
            if (peek() != character) fail()
            index++
        }

        private fun fail(): Nothing = throw JsonSyntaxException()
    }
}
