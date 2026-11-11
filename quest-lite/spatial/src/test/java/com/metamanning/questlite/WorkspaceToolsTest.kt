package com.metamanning.questlite

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

class WorkspaceToolsTest {
    private val room = UUID.randomUUID().toString()
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
    @Test fun browserAndWidgetContentSurviveLayoutExportImport() {
        val layout = RoomLayout(room, "Studio", listOf(
            WindowLayout(slot = 0, kind = "browser", url = "https://example.com/search?q=notes#results"),
            WindowLayout(slot = 1, kind = "widget", html = "<h1>My dashboard</h1><script>console.log('local')</script>", network = true)
        ))
        assertEquals(layout, RoomLayoutCodec.decode(RoomLayoutCodec.encode(layout), room))
    }
    @Test fun legacyFourConversationLayoutMigratesWithoutLosingIdsOrPositions() {
        val window = WindowLayout(slot = 2, url = "https://chatgpt.com/c/conversation", position = listOf(1f, 2f, 3f))
        val old = JSONObject(RoomLayoutCodec.encode(RoomLayout(room, "Office", listOf(window)))).put("version", 1)
        val entry = old.getJSONArray("windows").getJSONObject(0)
        entry.remove("kind"); entry.remove("html"); entry.remove("network")
        val restored = RoomLayoutCodec.decode(old.toString(), room)
        assertEquals(window, restored.windows.single())
        assertEquals(2, JSONObject(RoomLayoutCodec.encode(restored)).getInt("version"))
    }
    @Test fun unsupportedKindsAndMisplacedWidgetCapabilitiesAreRejected() {
        rejected { RoomLayoutCodec.encode(RoomLayout(room,"Room",listOf(WindowLayout(slot=0,kind="native-shell")))) }
        rejected { RoomLayoutCodec.encode(RoomLayout(room,"Room",listOf(WindowLayout(slot=0,html="<script>bad()</script>")))) }
        rejected { RoomLayoutCodec.encode(RoomLayout(room,"Room",listOf(WindowLayout(slot=0,kind="browser",url="https://example.com",network=true)))) }
    }
    @Test fun htmlLimitIsUtf8BytesRatherThanCharacters() {
        rejected { RoomLayoutCodec.encode(RoomLayout(room,"Room",listOf(WindowLayout(slot=0,kind="widget",html="🧩".repeat(40000))))) }
    }
    @Test fun browserAddressPolicyIsSharedWithRestoreAndRejectsPrivilegedSchemes() {
        assertTrue(WebAddress.allowedHttps("https://example.com:8443/path"))
        assertTrue(RoomLayoutCodec.safeBrowserUrl("HTTPS://example.com/"))
        listOf("javascript:alert(1)", "file:///data/data/app/private", "content://private/1", "http://example.com", "https://user:secret@example.com", "https://example.com:0/", "https://example.com:99999/").forEach { assertFalse(it, WebAddress.allowedHttps(it)) }
    }
    @Test fun widgetStateIsBoundedJsonObjectWithControlledDepth() {
        assertTrue(WidgetStatePolicy.valid("{\"notes\":\"escaped \\\" { text }\",\"done\":[true,false]}"))
        assertFalse(WidgetStatePolicy.valid("[]")); assertFalse(WidgetStatePolicy.valid("{} trailing"))
        assertFalse(WidgetStatePolicy.valid("{\"x\":\""+"x".repeat(32768)+"\"}"))
        assertFalse(WidgetStatePolicy.valid("{\"x\":".repeat(40)+"{}"+"}".repeat(40)))
        assertFalse(WidgetStatePolicy.valid("{\"bad\":]"))
    }
    @Test fun importedWidgetsCannotInheritNetworkPermission() {
        val saved=RoomLayout(room,"Room",listOf(WindowLayout(slot=0,kind="widget",html="<h1>Remote tool</h1>",network=true)))
        val imported=RoomLayoutCodec.offlineImport(saved)
        assertFalse(imported.windows.single().network)
        assertEquals(saved.windows.single().html,imported.windows.single().html)
        assertTrue(saved.windows.single().network)
    }
}
