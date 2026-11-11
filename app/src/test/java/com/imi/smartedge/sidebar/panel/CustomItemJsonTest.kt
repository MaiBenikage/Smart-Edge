package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON round-trip tests for the CustomItem list that PanelPreferences
 * persists to SharedPreferences.
 *
 * These tests call the real production encode/decode path
 * ([CustomItemJson], which `PanelPreferences.getCustomItems()` /
 * `setCustomItems()` delegate to) — they do not build `JSONObject`s
 * directly and assert the library round-trips them. If the schema or
 * the validation changes, this test fails.
 *
 * Schema: a JSON array of objects with `id` (UUID), `isUrl`, `title`,
 * `content`. Note `tel:` is NOT an allowed custom-item scheme
 * (see CustomItemValidation): a `tel:` fixture would imply a valid
 * item the app cannot create, so it is not used here.
 */
class CustomItemJsonTest {

    private val uuid1 = "11111111-1111-1111-1111-111111111111"
    private val uuid2 = "22222222-2222-2222-2222-222222222222"
    private val uuid3 = "33333333-3333-3333-3333-333333333333"

    @Test fun `list roundtrips through encode then decode`() {
        val input = listOf(
            CustomItem(id = uuid1, isUrl = true, title = "Site", content = "https://example.com"),
            CustomItem(id = uuid2, isUrl = false, title = "Maps", content = "intent:#Intent;component=com.google.android.apps.maps/.MapsActivity;end"),
            CustomItem(id = uuid3, isUrl = true, title = "Search", content = "https://example.com/search?q=x")
        )
        val decoded = CustomItemJson.decode(CustomItemJson.encode(input))
        assertEquals(input, decoded)
    }

    @Test fun `unicode title roundtrips`() {
        val input = listOf(CustomItem(id = uuid1, isUrl = true, title = "中文标题", content = "https://example.com"))
        val decoded = CustomItemJson.decode(CustomItemJson.encode(input))
        assertEquals(1, decoded.size)
        assertEquals("中文标题", decoded[0].title)
    }

    @Test fun `empty list survives`() {
        assertEquals(emptyList<CustomItem>(), CustomItemJson.decode(CustomItemJson.encode(emptyList())))
        assertEquals(emptyList<CustomItem>(), CustomItemJson.decode("[]"))
    }

    @Test fun `malformed input decodes to empty, not a crash`() {
        // A single JSON object (not an array) is not a valid document.
        assertEquals(emptyList<CustomItem>(), CustomItemJson.decode("""{"id":"lonely","title":"lonely"}"""))
        // Not JSON at all.
        assertEquals(emptyList<CustomItem>(), CustomItemJson.decode("not json"))
        // Missing / blank.
        assertEquals(emptyList<CustomItem>(), CustomItemJson.decode(null))
        assertEquals(emptyList<CustomItem>(), CustomItemJson.decode("   "))
    }

    @Test fun `non-UUID ids are dropped on decode`() {
        // An imported/corrupt row with a non-UUID id renders but can never
        // be saved back (addCustomItem rejects it) — decode drops it.
        val raw = """[{"id":"not-a-uuid","isUrl":true,"title":"Bad","content":"https://example.com"},{"id":"$uuid1","isUrl":true,"title":"Good","content":"https://example.com"}]"""
        val decoded = CustomItemJson.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(uuid1, decoded[0].id)
    }

    @Test fun `duplicate ids are deduped on decode (first wins)`() {
        val raw = """[{"id":"$uuid1","isUrl":true,"title":"First","content":"https://one.example"},{"id":"$uuid1","isUrl":true,"title":"Second","content":"https://two.example"}]"""
        val decoded = CustomItemJson.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("First", decoded[0].title)
    }

    @Test fun `content is trimmed on decode`() {
        // Legacy rows with surrounding whitespace must still match
        // startsWith("intent:") downstream.
        val raw = """[{"id":"$uuid1","isUrl":false,"title":"  Maps  ","content":"  intent:#Intent;component=com.example/.Main;end  "}]"""
        val decoded = CustomItemJson.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("Maps", decoded[0].title)
        assertTrue(decoded[0].content.startsWith("intent:"))
    }

    @Test fun `rows missing id are dropped, other rows survive`() {
        val raw = """[{"title":"No id","content":"https://example.com"},{"id":"$uuid1","isUrl":true,"title":"Good","content":"https://example.com"}]"""
        val decoded = CustomItemJson.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(uuid1, decoded[0].id)
    }
}
