package com.metamanning.questlite

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThoughtWorldTest {
    private var time = 5_000L
    private var sequence = 0
    private fun id(): String {
        sequence += 1
        return UUID.nameUUIDFromBytes("world-$sequence".toByteArray()).toString()
    }
    private fun graph() = ThoughtGraph({ time }, ::id)
    private fun provenance(source: String = "user") = Provenance(source, "local", time.also { time += 1_000 })

    private fun shareable(library: ThoughtGraph, title: String, content: String = "local note"): Thought {
        val thought = library.create(ThoughtType.NOTE, title, content, ThoughtPrivacy.SHAREABLE, Vec3(0f, 0f, 0f), provenance())
        return thought
    }

    @Test fun curatedEnvironmentsFallBackWhenFramesSlip() {
        assertEquals(listOf("passthrough", "observatory", "design-studio"), EnvironmentCatalog.curated().map { it.id })
        assertTrue(EnvironmentCatalog.curated().all { !it.locomotionForced && it.bundled && it.license.isNotBlank() })
        val calm = EnvironmentCatalog.select("observatory", measuredFrameMs = null)
        assertEquals("observatory", calm.active.id)
        assertFalse(calm.active.passthrough)
        val slipped = EnvironmentCatalog.select("design-studio", EnvironmentCatalog.FRAME_BUDGET_MS + 2f)
        assertTrue(slipped.fallback)
        assertEquals("passthrough", slipped.active.id)
        assertTrue(runCatching { EnvironmentCatalog.select("marketplace-pack", null) }.isFailure)
        assertTrue(runCatching {
            EnvironmentCatalog.accept(EnvironmentManifest("observatory", EnvironmentCatalog.OBSERVATORY.license, true, "https://assets.example/pack"))
        }.isFailure)
        assertEquals("observatory", EnvironmentCatalog.accept(EnvironmentManifest("observatory", EnvironmentCatalog.OBSERVATORY.license, true)).id)
    }

    @Test fun frameBudgetUsesTheQuestFrameWindow() {
        val frames = FrameBudget()
        repeat(11) { frames.add(20f) }
        assertFalse(frames.exceeded())
        frames.add(20f)
        assertTrue(frames.exceeded())
        val recovered = FrameBudget()
        repeat(12) { recovered.add(8f) }
        assertFalse(recovered.exceeded())
    }

    @Test fun coThinkSharesOnlyApprovedObjects() {
        val library = graph()
        val note = shareable(library, "Shared note")
        val secret = library.create(ThoughtType.NOTE, "Secret", "diary", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val chat = library.create(ThoughtType.CONVERSATION, "Chat", "https://chatgpt.com/c/private", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val cookie = library.create(ThoughtType.NOTE, "Cookie", "session cookie=abc", ThoughtPrivacy.SHAREABLE, Vec3(0f, 0f, 0f), provenance())
        library.link(note.id, secret.id, "related", "", provenance())
        val host = CoThinkSession(UUID.randomUUID().toString(), SessionRole.HOST)
        assertTrue(runCatching { host.approve(library, listOf(secret.id)) }.isFailure)
        assertTrue(runCatching { host.approve(library, listOf(chat.id)) }.isFailure)
        assertTrue(runCatching { host.approve(library, listOf(cookie.id)) }.isFailure)
        host.approve(library, listOf(note.id))
        val envelope = host.envelope(library)
        val json = encodeEnvelope(envelope)
        val root = JSONObject(json)
        assertFalse(root.has("anchors") || root.has("cookies") || root.has("camera") || root.has("windows"))
        assertEquals(listOf(note.id), decodeEnvelope(json).thoughts.map { it.id })
        assertTrue(decodeEnvelope(json).links.isEmpty())
        val guestLibrary = graph()
        val localSecret = guestLibrary.create(ThoughtType.NOTE, "Mine", "stay", ThoughtPrivacy.PRIVATE, Vec3(1f, 0f, 0f), provenance("user"))
        importEnvelope(guestLibrary, envelope, provenance("import"))
        assertEquals("stay", guestLibrary.requireThought(localSecret.id).contentRef)
        assertEquals(note.title, guestLibrary.requireThought(note.id).title)
        assertTrue(guestLibrary.anchors().isEmpty())
        val guest = CoThinkSession(envelope.sessionId, SessionRole.GUEST)
        assertTrue(runCatching { guest.approve(guestLibrary, listOf(note.id)) }.isFailure)
    }

    @Test fun guestImportDoesNotReplaceAPrivateThoughtWithTheSameId() {
        val library = graph()
        val note = shareable(library, "Public")
        val host = CoThinkSession(UUID.randomUUID().toString(), SessionRole.HOST)
        host.approve(library, listOf(note.id))
        val envelope = host.envelope(library)
        var issued = 0
        val guest = ThoughtGraph({ time }) {
            issued += 1
            if (issued == 1) note.id else UUID.nameUUIDFromBytes("guest-$issued".toByteArray()).toString()
        }
        val kept = guest.create(ThoughtType.NOTE, "Private copy", "mine", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        assertEquals(note.id, kept.id)
        importEnvelope(guest, envelope, provenance("import"))
        assertEquals("mine", guest.requireThought(note.id).contentRef)
        assertEquals(ThoughtPrivacy.PRIVATE, guest.requireThought(note.id).privacy)
    }

    @Test fun lookAndAskSubmitsOnlyAfterAnIndicatorAndAKeptSnapshot() {
        val look = LookAndAsk { UUID.nameUUIDFromBytes("bridge".toByteArray()).toString() }
        assertTrue(runCatching { look.arm() }.isFailure)
        look.optIn()
        assertTrue(look.arm().contains("CAPTURE ON"))
        assertTrue(look.indicatorVisible)
        assertTrue(runCatching { look.submit("what is this?") }.isFailure)
        look.capture("local-snapshot:${UUID.randomUUID()}")
        assertEquals(CapturePhase.CAPTURED, look.phase)
        val request = look.submit("What is this object?")
        assertFalse(request.claimsWebsiteToolAccess)
        assertFalse(look.indicatorVisible)
        assertEquals(CapturePhase.SUBMITTED, look.phase)
        look.cancel()
        assertEquals(CapturePhase.IDLE, look.phase)
        assertNull(look.pending)
    }

    @Test fun buildBenchKeepsWidgetsOfflineAndBrowsersSeparate() {
        val library = graph()
        val page = library.create(ThoughtType.REFERENCE, "Docs", "https://example.com/guide", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val sketch = library.create(ThoughtType.NOTE, "Sketch", "<script>alert(1)</script>", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val chat = library.create(ThoughtType.CONVERSATION, "Chat", "https://chatgpt.com/c/abc", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val items = BuildBench.materials(library, listOf(page.id, sketch.id, chat.id))
        assertEquals("browser", items[0].kind)
        assertEquals("", items[0].html)
        assertFalse(items[0].network)
        assertEquals("widget", items[1].kind)
        assertFalse(items[1].network)
        assertFalse(items[1].html.contains("<script>"))
        assertTrue(items[1].html.contains("&lt;script&gt;"))
        assertEquals("assistant", items[2].kind)
        assertTrue(runCatching { BuildBench.materials(library, listOf(library.create(ThoughtType.REFERENCE, "Bad", "javascript:alert(1)", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance()).id)) }.isFailure)
    }

    @Test fun retrievalDoesNotClaimWebsiteToolsOrCopyConversationUrls() {
        val library = graph()
        val note = library.create(ThoughtType.NOTE, "Bridge", "span", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val chat = library.create(ThoughtType.CONVERSATION, "Chat", "https://chatgpt.com/c/secret", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val prompt = Retrieval.prompt("how does the bridge work?", listOf(note, chat))
        assertTrue(prompt.contains("does not call ChatGPT tools"))
        assertTrue(prompt.contains("Bridge"))
        assertFalse(prompt.contains("chatgpt.com"))
        assertFalse(prompt.contains("secret"))
    }
}
