package com.metamanning.questlite

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThoughtLibraryTest {
    private var time = 1_000L
    private var sequence = 0
    private fun clock() = time
    private fun id(): String {
        sequence += 1
        return UUID.nameUUIDFromBytes("thought-$sequence".toByteArray()).toString()
    }
    private fun graph() = ThoughtGraph(::clock, ::id)
    private fun provenance(note: String = "") = Provenance("user", "local", time.also { time += 1_000 }, note)
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun libraryIsIndependentOfTheWindowLimit() {
        val library = graph()
        val made = List(RoomLayoutCodec.MAX_WINDOWS + 2) { index ->
            library.create(ThoughtType.NOTE, "Note $index", "body $index", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        }
        assertEquals(RoomLayoutCodec.MAX_WINDOWS + 2, library.thoughts().size)
        assertEquals(made.map { it.id }.toSet(), library.thoughts().map { it.id }.toSet())
        rejected { RoomLayoutCodec.encode(RoomLayout(UUID.randomUUID().toString(), "Room", List(7) { WindowLayout(slot = it) })) }
    }

    @Test fun idsStayStableAcrossEditsAndSearchFindsEvidence() {
        val library = graph()
        val root = library.create(ThoughtType.CONCEPT, "Memory", "palace", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val paper = library.create(ThoughtType.REFERENCE, "Paper", "notes", ThoughtPrivacy.PRIVATE, Vec3(0.2f, 0f, 0f), provenance())
        library.link(root.id, paper.id, "evidence", "doi:10.1000/luma", provenance())
        library.update(root.id, provenance()) { it.copy(title = "Memory palace") }
        assertEquals(root.id, library.requireThought(root.id).id)
        assertTrue(library.search("doi:10.1000/luma").map { it.id }.contains(paper.id))
        assertEquals(listOf(root.id), library.search("memory").map { it.id })
        assertEquals(paper.id, library.search("ref").single().id)
    }

    @Test fun historyMatchesStoredEventsAndUndoDoesNotRewriteIt() {
        val library = graph()
        val created = library.create(ThoughtType.TASK, "Draft", "v1", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        library.update(created.id, provenance()) { it.copy(title = "Draft two") }
        assertEquals("Draft", library.stateAt(1).thoughts.single().title)
        assertEquals("Draft two", library.stateAt(2).thoughts.single().title)
        assertEquals(library.snapshot(), library.stateAt(library.events().size))
        library.undo()
        assertEquals("Draft", library.requireThought(created.id).title)
        assertEquals("Draft two", library.stateAt(2).thoughts.single().title)
        assertEquals("Draft", library.stateAt(3).thoughts.single().title)
        assertEquals(listOf("create", "update", "undo"), library.events().map { it.kind })
    }

    @Test fun deleteNeedsConfirmationAndTossDoesNotDelete() {
        val library = graph()
        val note = library.create(ThoughtType.NOTE, "Keep me", "text", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val cluster = library.createCluster("Board", Vec3(1f, 0f, 0f), provenance())
        rejected { library.delete(note.id, confirmed = false, provenance()) }
        assertNotNull(library.find(note.id))
        val preview = library.tossPreview(note.id, Vec3(0f, 0f, 0f), cluster.name)
        val tossed = library.commitToss(preview, provenance())
        assertEquals(note.id, tossed.id)
        assertEquals(1, library.thoughts().size)
        assertEquals(cluster.id, tossed.clusterId)
        assertEquals(1, library.links().size.let { library.thoughts().size })
        library.undo()
        assertNull(library.requireThought(note.id).clusterId)
        assertEquals(Vec3(0f, 0f, 0f), library.requireThought(note.id).coordinates)
    }

    @Test fun namedTossIsDeterministicAndAFlickBreaksTiesByNameThenId() {
        val library = graph()
        val note = library.create(ThoughtType.NOTE, "Stone", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        library.createCluster("Atlas", Vec3(2f, 0f, 0f), provenance())
        library.createCluster("Atlas", Vec3(-2f, 0f, 0f), provenance())
        val first = library.tossPreview(note.id, Vec3(0f, 0f, 0f), "atlas")
        val second = library.tossPreview(note.id, Vec3(0f, 0f, 0f), "ATLAS")
        assertEquals(first, second)
        assertEquals(library.clusters().minBy { it.id }.id, first.clusterId)
        val higher = library.clusters().maxBy { it.id }
        val onto = library.tossOnto(note.id, higher.id)
        assertEquals(higher.id, library.commitToss(onto, provenance()).clusterId)
        library.undo()
        val left = library.createCluster("Left", Vec3(-1f, 0f, 0f), provenance())
        val right = library.createCluster("Right", Vec3(1f, 0f, 0f), provenance())
        val flicked = library.tossPreview(note.id, Vec3(1f, 0f, 0f), null)
        assertEquals(right.id, flicked.clusterId)
        assertEquals(left.id, library.tossPreview(note.id, Vec3(-1f, 0f, 0f), null).clusterId)
    }

    @Test fun constellationIsStableBoundedAndKeepsOmittedThoughts() {
        val library = graph()
        val root = library.create(ThoughtType.CONCEPT, "Root", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val children = List(12) { index ->
            val child = library.create(ThoughtType.CONCEPT, "Child $index", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
            library.link(root.id, child.id, "evidence", "exhibit-$index", provenance())
            child
        }
        val layout = library.layoutConstellation(root.id)
        val again = library.layoutConstellation(root.id)
        assertEquals(ThoughtGraph.MAX_CONSTELLATION, layout.nodes.size)
        assertEquals(1, layout.omitted)
        assertEquals(layout.nodes.map { it.thought.id to it.position }, again.nodes.map { it.thought.id to it.position })
        assertEquals(root.id, layout.nodes.first { it.root }.thought.id)
        assertTrue(layout.nodes.filter { !it.root }.all { it.evidence })
        assertEquals(children.map { it.id }.toSet(), library.neighbors(root.id).map { it.id }.toSet())
        library.commitConstellation(root.id, provenance())
        assertEquals(13, library.thoughts().size)
        assertEquals(root.id, library.requireThought(root.id).id)
    }

    @Test fun focusRestoresVisibilityAndDoesNotDropWork() {
        val library = graph()
        val root = library.create(ThoughtType.CONCEPT, "Focus", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val linked = library.create(ThoughtType.NOTE, "Inside", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val other = library.create(ThoughtType.NOTE, "Outside", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        library.link(root.id, linked.id, "supports", "", provenance())
        val panel = UUID.randomUUID().toString()
        library.attachPanel(root.id, panel, provenance())
        val cards = mapOf(root.id to true, linked.id to true, other.id to true)
        val windows = mapOf(panel to true, UUID.randomUUID().toString() to true)
        val bubble = library.planFocus(root.id, cards, windows)
        assertEquals(cards, bubble.previousCards)
        assertEquals(windows, bubble.previousWindows)
        assertTrue(bubble.cardVisibility.getValue(root.id))
        assertTrue(bubble.cardVisibility.getValue(linked.id))
        assertFalse(bubble.cardVisibility.getValue(other.id))
        assertTrue(bubble.windowVisibility.getValue(panel))
        assertFalse(bubble.windowVisibility.getValue(windows.keys.first { it != panel }))
        assertEquals(3, library.thoughts().size)
    }

    @Test fun palaceStaysInsideTheRecognizedRoom() {
        val library = graph()
        val note = library.create(ThoughtType.NOTE, "Lamp", "", ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance())
        val roomA = UUID.randomUUID().toString()
        val roomB = UUID.randomUUID().toString()
        library.anchor(note.id, roomA, Vec3(1f, 1.4f, 2f), listOf(1f, 0f, 0f, 0f), provenance())
        assertEquals(Vec3(1f, 1.4f, 2f), library.palace(roomA, roomA).single().position)
        assertTrue(library.palace(roomB, roomB).isEmpty())
        rejected { library.palace(roomA, roomB) }
        assertEquals(Vec3(0f, 0f, 0f), library.requireThought(note.id).coordinates)
    }

    @Test fun savedHistoryMustMatchTheLibrary() {
        val library = graph()
        library.create(ThoughtType.NOTE, "Round", "trip", ThoughtPrivacy.ROOM, Vec3(0.5f, 0f, 0.25f), provenance())
        val saved = ThoughtGraph.encode(library)
        val restored = ThoughtGraph.decode(saved)
        assertEquals(library.thoughts().map { it.title to it.privacy }, restored.thoughts().map { it.title to it.privacy })
        assertEquals(library.events().map { it.kind }, restored.events().map { it.kind })
        val tampered = JSONObject(saved)
        tampered.getJSONArray("thoughts").getJSONObject(0).put("title", "Changed")
        rejected { ThoughtGraph.decode(tampered.toString()) }
        rejected { ThoughtGraph.decode("{") }
        rejected { ThoughtGraph.decode(" ".repeat(ThoughtGraph.MAX_BYTES + 1)) }
    }

    @Test fun conversationsCannotBeMarkedShareable() {
        val library = graph()
        val chat = library.create(ThoughtType.CONVERSATION, "Chat", "https://chatgpt.com/c/abc", ThoughtPrivacy.SHAREABLE, Vec3(0f, 0f, 0f), provenance())
        assertEquals(ThoughtPrivacy.PRIVATE, chat.privacy)
        rejected { library.update(chat.id, provenance()) { it.copy(privacy = ThoughtPrivacy.SHAREABLE) } }
        assertFalse(canShare(library.requireThought(chat.id)))
    }
}
