package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniJsonTest {
    @Test
    fun parsed_documents_can_be_rewritten_without_changing_number_text_or_escaping() {
        val source = """{"count":9007199254740993,"label":"line\\n\\\"quoted\\\"","enabled":true}"""
        val parsed = MiniJson.parseOrNull(source)

        assertEquals(source, MiniJson.stringify(parsed!!))
    }


    @Test
    fun reads_a_top_level_boolean() {
        val document = MiniJson.parseOrNull("""{"wslEngineEnabled": true, "autoStart": false}""")
        assertEquals(true, MiniJson.booleanField(document, "wslEngineEnabled"))
        assertEquals(false, MiniJson.booleanField(document, "autoStart"))
    }

    @Test
    fun a_missing_field_is_null_rather_than_false() {
        val document = MiniJson.parseOrNull("""{"other": 1}""")
        assertNull(MiniJson.booleanField(document, "wslEngineEnabled"))
    }

    @Test
    fun a_non_boolean_field_is_not_coerced() {
        val document = MiniJson.parseOrNull("""{"wslEngineEnabled": "true"}""")
        assertNull(MiniJson.booleanField(document, "wslEngineEnabled"))
    }

    @Test
    fun nested_objects_arrays_and_escapes_parse() {
        val text = """
            {
              "wslEngineEnabled": true,
              "filesharingDirectories": ["C:\\Users\\test", "D:\\data"],
              "proxy": { "http": null, "port": 3128.0 },
              "unicode": "\u0414\u043e\u043a\u0435\u0440"
            }
        """.trimIndent()
        val document = MiniJson.parseOrNull(text)
        assertTrue(document is JsonValue.JsonObject)
        assertEquals(true, MiniJson.booleanField(document, "wslEngineEnabled"))

        val entries = document.entries
        assertTrue(entries["filesharingDirectories"] is JsonValue.JsonArray)
        assertEquals(2, (entries["filesharingDirectories"] as JsonValue.JsonArray).items.size)
        assertEquals(JsonValue.JsonString("Докер"), entries["unicode"])
    }

    @Test
    fun invalid_or_truncated_json_is_rejected_instead_of_partially_accepted() {
        assertNull(MiniJson.parseOrNull(null))
        assertNull(MiniJson.parseOrNull(""))
        assertNull(MiniJson.parseOrNull("{"))
        assertNull(MiniJson.parseOrNull("""{"wslEngineEnabled": tru}"""))
        assertNull(MiniJson.parseOrNull("""{"a": 1} trailing"""))
        assertNull(MiniJson.parseOrNull("wslEngineEnabled=true"))
    }

    @Test
    fun an_empty_object_parses_and_answers_nothing() {
        val document = MiniJson.parseOrNull("{}")
        assertTrue(document is JsonValue.JsonObject)
        assertNull(MiniJson.booleanField(document, "wslEngineEnabled"))
    }
}
