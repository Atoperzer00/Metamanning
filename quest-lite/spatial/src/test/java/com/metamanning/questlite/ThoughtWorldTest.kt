package com.metamanning.questlite

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
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

    @Test fun openingScenePlacesLinkedThoughtsWhereTheControlsCanMoveThem() {
        assertFalse(GestureMachine().headHandRayEnabled)
        val library = graph()
        val ids = OpeningScene.seed(library) { note -> provenance().copy(note = note) }
        assertEquals(4, ids.size)
        val opened = OpeningScene.cardsToOpen(library)
        assertEquals(ids[0], opened.first())
        assertEquals(ids.toSet(), opened.toSet())
        val spots = ids.map { library.find(it)!!.coordinates }
        assertEquals(OpeningScene.spread(4), spots)
        assertTrue(spots.all { it != OpeningScene.PULL_HOME })
        assertTrue(spots.zip(ReadingArc.seats(4)).all { (placed, seat) -> (placed - seat).length() > 0.2f })
        assertTrue(spots.all { ReadingArc.comfortable(PullPolicy.DISTANCE_M + it.z) })
        assertEquals("Board", library.clusters().single().name)
        assertEquals(OpeningScene.BOARD, library.clusters().single().anchor)
        val question = ids[2]
        val steps = TaskSteps.read(library.requireThought(question).contentRef)
        assertEquals(listOf("remember", "source"), steps?.map { it.id })
        assertTrue(steps!!.none { it.done })
        val focus = library.planFocus(question, ids.associateWith { true }, emptyMap())
        assertTrue(focus.cardVisibility.getValue(question))
        assertTrue(focus.cardVisibility.getValue(ids[0]))
        assertFalse(focus.cardVisibility.getValue(ids[1]))
        assertFalse(focus.cardVisibility.getValue(ids[3]))
        val fanned = library.layoutConstellation(ids[0])
        assertTrue(fanned.nodes.any { (it.position - library.find(it.thought.id)!!.coordinates).length() > 0.2f })
        library.move(ids[1], OpeningScene.PULL_HOME, provenance())
        assertEquals(OpeningScene.PULL_HOME, library.find(ids[1])!!.coordinates)
        assertFalse(OpeningScene.stacked(OpeningScene.spread(4)))
        assertTrue(OpeningScene.stacked(List(3) { Vec3(0f, 0f, 0f) }))
    }

    @Test fun ideaHistoryShowsOneThoughtWithoutRewritingTheLog() {
        val library = graph()
        val note = shareable(library, "Orbit", "first")
        val other = shareable(library, "Other")
        val bystander = shareable(library, "Bystander")
        library.update(note.id, provenance()) { it.copy(title = "Orbit revised", contentRef = "second") }
        library.link(note.id, other.id, "evidence", "notebook", provenance())
        val before = library.events().size
        val steps = library.ideaHistory(note.id)
        assertEquals(before, library.events().size)
        assertEquals(listOf("create", "update", "link"), steps.map { it.kind })
        assertEquals("Orbit revised", steps[1].title)
        assertEquals("second", steps[1].contentRef)
        assertTrue(steps.all { it.present })
        assertEquals(listOf("create", "link"), library.ideaHistory(other.id).map { it.kind })
        assertEquals(listOf("create"), library.ideaHistory(bystander.id).map { it.kind })
        library.delete(note.id, true, provenance())
        val removed = library.ideaHistory(note.id).last()
        assertEquals("delete", removed.kind)
        assertFalse(removed.present)
        assertEquals(before + 1, library.events().size)
    }

    @Test fun curatedEnvironmentsFallBackWhenFramesSlip() {
        assertEquals(listOf("passthrough", "observatory", "design-studio"), EnvironmentCatalog.curated().map { it.id })
        assertTrue(EnvironmentCatalog.curated().all { !it.locomotionForced && it.bundled && it.license.isNotBlank() })
        val calm = EnvironmentCatalog.select("observatory", measuredFrameMs = null)
        assertEquals("observatory", calm.active.id)
        assertFalse(calm.active.passthrough)
        assertTrue(calm.active.lod.size >= 2)
        assertTrue(calm.active.lights.isNotEmpty() && calm.active.occlusion.isNotEmpty())
        assertEquals(0, calm.lod?.level)
        assertTrue(calm.reason.contains("LOD 0"))
        assertFalse(calm.reason.contains("color and light only"))
        val slipped = EnvironmentCatalog.select("design-studio", EnvironmentCatalog.FRAME_BUDGET_MS + 2f)
        assertTrue(slipped.fallback)
        assertEquals("passthrough", slipped.active.id)
        assertTrue(runCatching { EnvironmentCatalog.select("marketplace-pack", null) }.isFailure)
        assertTrue(runCatching {
            EnvironmentCatalog.accept(EnvironmentManifest("observatory", EnvironmentCatalog.OBSERVATORY.license, true, "https://assets.example/pack"))
        }.isFailure)
        assertEquals("observatory", EnvironmentCatalog.accept(EnvironmentManifest("observatory", EnvironmentCatalog.OBSERVATORY.license, true)).id)
    }

    @Test fun curatedPackagesDownloadOverTheLoopbackAndMatchTheAssetFiles() {
        val shelf = CuratedPackageShelf(CuratedChannel.IDS.associateWith { CuratedChannel.fetch(it) })
        try {
            val source = shelf.source()
            val installer = EnvironmentInstaller(source)
            val installed = installer.download("observatory")
            assertEquals(1, source.fetches)
            assertEquals("curated-channel", installed.redistribution)
            assertTrue(installed.lod.size >= 2 && installed.lights.isNotEmpty() && installed.occlusion.isNotEmpty())
            assertFalse(installed.locomotionForced)
            val finest = installer.activate("observatory", null)
            assertEquals(0, finest.lod?.level)
            assertEquals(1, source.fetches)
            val coarse = installer.activate("observatory", installed.frameBudgetMs - 3f)
            assertEquals(1, coarse.lod?.level)
            assertFalse(coarse.fallback)
            assertEquals(1, source.fetches)
            val slipped = installer.activate("design-studio", EnvironmentCatalog.FRAME_BUDGET_MS + 2f)
            assertTrue(slipped.fallback)
            assertEquals("passthrough", slipped.active.id)
            assertEquals(2, source.fetches)
            installer.activate("design-studio", null)
            assertEquals(2, source.fetches)
            assertTrue(runCatching { installer.download("marketplace-pack") }.isFailure)
            assertTrue(runCatching { source.fetch("https://assets.example/pack") }.isFailure)
            assertEquals(2, source.fetches)
            val tampered = CuratedChannel.fetch("observatory").copyOf()
            tampered[tampered.lastIndex] = (tampered[tampered.lastIndex] + 1).toByte()
            assertTrue(runCatching { EnvironmentPackages.decode(tampered) }.isFailure)
            val module = listOf(File("."), File("quest-lite/spatial")).first { File(it, "src/main/java").isDirectory }
            val directory = File(module, "src/main/assets/environments")
            for (id in CuratedChannel.IDS) {
                val file = File(directory, "$id.lumaenv")
                assertTrue(file.isFile)
                assertArrayEquals(CuratedChannel.fetch(id), file.readBytes())
            }
        } finally {
            shelf.close()
        }
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

    @Test fun twoSessionsExchangeOnlyApprovedNodes() {
        val library = graph()
        val note = shareable(library, "Shared note")
        val other = shareable(library, "Linked note")
        val secret = library.create(ThoughtType.NOTE, "Secret", "diary", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val chat = library.create(ThoughtType.CONVERSATION, "Chat", "https://chatgpt.com/c/private", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val cookie = library.create(ThoughtType.NOTE, "Cookie", "session cookie=abc", ThoughtPrivacy.SHAREABLE, Vec3(0f, 0f, 0f), provenance())
        val camera = library.create(ThoughtType.NOTE, "Camera", "camera:living-room", ThoughtPrivacy.SHAREABLE, Vec3(0f, 0f, 0f), provenance())
        val unapproved = shareable(library, "Keep local")
        library.link(note.id, other.id, "related", "doi:10.1/example", provenance())
        library.link(note.id, secret.id, "related", "", provenance())
        val roomId = UUID.nameUUIDFromBytes("room".toByteArray()).toString()
        library.anchor(note.id, roomId, Vec3(0f, 0f, 0f), listOf(1f, 0f, 0f, 0f), provenance())
        val look = LookAndAsk { UUID.nameUUIDFromBytes("bridge-share".toByteArray()).toString() }
        look.optIn()
        look.arm()
        look.captureObject(note.id, note.title, "SECRET-PIXELS-999".toByteArray())
        val host = CoThinkSession(UUID.randomUUID().toString(), SessionRole.HOST)
        assertTrue(runCatching { host.approve(library, listOf(camera.id)) }.isFailure)
        host.approve(library, listOf(note.id, other.id))
        val server = CoThinkHost(host.id, { host }, { library })
        try {
            val before = library.thoughts().size
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.port), 3_000)
                socket.soTimeout = 3_000
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("""{"version":1,"type":"push","role":"GUEST","sessionId":"${host.id}","contentRef":"guest-secret-payload"}""")
                writer.newLine()
                writer.flush()
                val reply = socket.getInputStream().bufferedReader().readLine()
                assertTrue(reply.contains(""""type":"error""""))
            }
            assertEquals(before, library.thoughts().size)
            assertTrue(library.thoughts().none { it.contentRef.contains("guest-secret-payload") })
            val line = requestShare("127.0.0.1", server.port, host.id)
            assertFalse(line.contains("SECRET-PIXELS"))
            assertFalse(line.contains("cookie"))
            assertFalse(line.contains("camera:"))
            assertFalse(line.contains("chatgpt.com"))
            assertFalse(line.contains(roomId))
            assertFalse(line.contains("anchors"))
            assertFalse(line.contains("guest-secret-payload"))
            val envelope = pullEnvelope("127.0.0.1", server.port, host.id)
            val guest = graph()
            val mine = guest.create(ThoughtType.NOTE, "Mine", "stay", ThoughtPrivacy.PRIVATE, Vec3(1f, 0f, 0f), provenance())
            val imported = importEnvelope(guest, envelope, provenance("import"))
            assertEquals(setOf(note.id, other.id), imported.map { it.id }.toSet())
            assertEquals(listOf("doi:10.1/example"), guest.links().map { it.evidenceRef })
            assertNull(guest.find(secret.id))
            assertNull(guest.find(chat.id))
            assertNull(guest.find(cookie.id))
            assertNull(guest.find(camera.id))
            assertNull(guest.find(unapproved.id))
            assertEquals("stay", guest.requireThought(mine.id).contentRef)
            assertTrue(guest.anchors().isEmpty())
            assertEquals(1, library.anchors().size)
            assertTrue(runCatching { pullEnvelope("127.0.0.1", server.port, UUID.randomUUID().toString()) }.isFailure)
            assertTrue(runCatching { requestShare("8.8.8.8", server.port, host.id) }.isFailure)
            assertEquals("diary", library.requireThought(secret.id).contentRef)
        } finally {
            server.close()
        }
    }

    @Test fun lookAndAskSubmitsOnlyAfterAnIndicatorAndAKeptSnapshot() {
        val look = LookAndAsk { UUID.nameUUIDFromBytes("bridge".toByteArray()).toString() }
        val objectId = UUID.nameUUIDFromBytes("object".toByteArray()).toString()
        val bytes = "span length 12".toByteArray()
        assertTrue(runCatching { look.arm() }.isFailure)
        look.optIn()
        assertTrue(look.arm().contains("CAPTURE ON"))
        assertTrue(look.indicatorVisible)
        assertTrue(runCatching { look.submit("what is this?") }.isFailure)
        assertTrue(runCatching { look.captureObject(objectId, "Bridge note", ByteArray(0)) }.isFailure)
        look.captureObject(objectId, "Bridge note", bytes)
        assertEquals(CapturePhase.CAPTURED, look.phase)
        assertEquals(bytes.size, look.snapshot?.byteLength)
        assertEquals(sha256Hex(bytes), look.snapshot?.sha256)
        val request = look.submit("What is this object?")
        assertEquals(objectId, request.objectId)
        assertEquals("Bridge note", request.objectLabel)
        assertFalse(request.snapshotRef.contains("span"))
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
