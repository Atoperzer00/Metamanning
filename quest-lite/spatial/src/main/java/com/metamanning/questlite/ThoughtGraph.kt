package com.metamanning.questlite

import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room-independent point. x is meters to the user's right, y is meters above reading height,
 * and z is extra distance beyond the 1.05 m reading plane. This is not an MRUK anchor.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val magnitude = length()
        if (magnitude < 1e-5f) return Vec3(0f, 0f, 1f)
        return this * (1f / magnitude)
    }
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

enum class ThoughtType { NOTE, REFERENCE, CONCEPT, CONVERSATION, MODEL, TASK }

enum class ThoughtPrivacy { PRIVATE, ROOM, SHAREABLE }

/** Where a thought came from. The ChatGPT website is not a native tool source. */
data class Provenance(val source: String, val actor: String, val atMillis: Long, val note: String = "") {
    init {
        require(source in SOURCES) { "Unknown provenance source" }
        require(actor.isNotBlank() && actor.length <= 80 && actor.none { it.code < 32 }) { "Invalid provenance actor" }
        require(atMillis >= 0) { "Invalid provenance time" }
        require(note.length <= 240 && note.none { it.code < 32 }) { "Invalid provenance note" }
    }
    companion object {
        val SOURCES = setOf("user", "import", "reviewed-bridge", "system")
    }
}

data class Thought(
    val id: String,
    val type: ThoughtType,
    val title: String,
    val contentRef: String,
    val provenance: Provenance,
    val privacy: ThoughtPrivacy,
    val coordinates: Vec3,
    val panelId: String? = null,
    val clusterId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(canonicalUuid(id)) { "Invalid thought id" }
        require(title.isNotBlank() && title.length <= 60 && title.none { it.code < 32 }) { "Invalid thought title" }
        require(contentRef.length <= 500 && contentRef.none { it.code < 32 }) { "Invalid thought content" }
        require(coordinates.finite() && listOf(coordinates.x, coordinates.y, coordinates.z).all { abs(it) <= 30f }) { "Thought is outside supported bounds" }
        panelId?.let { require(canonicalUuid(it)) { "Invalid panel id" } }
        clusterId?.let { require(canonicalUuid(it)) { "Invalid cluster id" } }
        require(type != ThoughtType.CONVERSATION || privacy == ThoughtPrivacy.PRIVATE) { "Conversations stay private" }
        require(createdAt >= 0 && updatedAt >= createdAt) { "Invalid thought times" }
    }
}

data class ThoughtLink(
    val id: String,
    val fromId: String,
    val toId: String,
    val relation: String,
    val evidenceRef: String = "",
) {
    init {
        require(canonicalUuid(id) && canonicalUuid(fromId) && canonicalUuid(toId)) { "Invalid link id" }
        require(fromId != toId) { "A thought cannot link to itself" }
        require(relation in RELATIONS) { "Unsupported link relation" }
        require(evidenceRef.length <= 200 && evidenceRef.none { it.code < 32 }) { "Invalid evidence reference" }
    }
    companion object {
        val RELATIONS = setOf("related", "evidence", "supports")
    }
}

data class ThoughtCluster(val id: String, val name: String, val anchor: Vec3) {
    init {
        require(canonicalUuid(id)) { "Invalid cluster id" }
        require(name.isNotBlank() && name.length <= 60 && name.none { it.code < 32 }) { "Invalid cluster name" }
        require(anchor.finite() && listOf(anchor.x, anchor.y, anchor.z).all { abs(it) <= 30f }) { "Cluster is outside supported bounds" }
    }
}

/** Room-local pose. Stored apart from [Thought.coordinates] so a room change cannot move the library. */
data class RoomAnchor(val thoughtId: String, val roomId: String, val position: Vec3, val rotationWxyz: List<Float>) {
    init {
        require(canonicalUuid(thoughtId) && canonicalUuid(roomId)) { "Invalid room anchor" }
        require(position.finite() && listOf(position.x, position.y, position.z).all { abs(it) <= 30f }) { "Anchor is outside supported bounds" }
        require(rotationWxyz.size == 4 && rotationWxyz.all { it.isFinite() }) { "Invalid anchor rotation" }
        val norm = rotationWxyz.sumOf { (it * it).toDouble() }
        require(norm in 0.9..1.1) { "Invalid anchor rotation" }
    }
    fun key() = anchorKey(thoughtId, roomId)
}

data class GraphDelta(
    val thoughts: List<Thought> = emptyList(),
    val removeThoughts: List<String> = emptyList(),
    val links: List<ThoughtLink> = emptyList(),
    val removeLinks: List<String> = emptyList(),
    val clusters: List<ThoughtCluster> = emptyList(),
    val removeClusters: List<String> = emptyList(),
    val anchors: List<RoomAnchor> = emptyList(),
    val removeAnchors: List<String> = emptyList(),
)

data class ThoughtEvent(
    val id: String,
    val atMillis: Long,
    val kind: String,
    val subjectId: String,
    val provenance: Provenance,
    val inverse: GraphDelta,
    val forward: GraphDelta,
) {
    init {
        require(canonicalUuid(id)) { "Invalid event id" }
        require(kind in KINDS) { "Unsupported thought event" }
        require(atMillis >= 0) { "Invalid event time" }
    }
    companion object {
        val KINDS = setOf("create", "update", "delete", "link", "unlink", "cluster", "toss", "move", "anchor", "constellation", "import", "undo")
    }
}

data class BrainSnapshot(
    val thoughts: List<Thought>,
    val links: List<ThoughtLink>,
    val clusters: List<ThoughtCluster>,
    val anchors: List<RoomAnchor>,
)

data class ConstellationNode(val thought: Thought, val position: Vec3, val evidence: Boolean, val root: Boolean)

data class ConstellationLayout(val nodes: List<ConstellationNode>, val omitted: Int)

data class TossPreview(
    val thoughtId: String,
    val clusterId: String,
    val clusterName: String,
    val landing: Vec3,
    val direction: Vec3,
)

data class FocusBubble(
    val rootId: String,
    val visibleThoughtIds: Set<String>,
    val previousCards: Map<String, Boolean>,
    val previousWindows: Map<String, Boolean>,
    val cardVisibility: Map<String, Boolean>,
    val windowVisibility: Map<String, Boolean>,
)

/**
 * Local thought library. Storage is independent of the six-window panel limit.
 * The event log is the history a time scrub reads; every mutation appends to it.
 */
class ThoughtGraph(private val clock: () -> Long, private val ids: () -> String = { UUID.randomUUID().toString() }) {
    private val thoughts = linkedMapOf<String, Thought>()
    private val links = linkedMapOf<String, ThoughtLink>()
    private val clusters = linkedMapOf<String, ThoughtCluster>()
    private val anchors = linkedMapOf<String, RoomAnchor>()
    private val events = mutableListOf<ThoughtEvent>()

    fun thoughts() = thoughts.values.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    fun links() = links.values.sortedBy { it.id }
    fun clusters() = clusters.values.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    fun anchors() = anchors.values.sortedWith(compareBy({ it.roomId }, { it.thoughtId }))
    fun events() = events.toList()
    fun find(id: String) = thoughts[id]
    fun requireThought(id: String) = thoughts[id] ?: error("Unknown thought")
    fun anchorFor(thoughtId: String, roomId: String) = anchors[anchorKey(thoughtId, roomId)]
    fun snapshot() = BrainSnapshot(thoughts(), links(), clusters(), anchors())

    fun create(
        type: ThoughtType,
        title: String,
        contentRef: String,
        privacy: ThoughtPrivacy,
        coordinates: Vec3,
        provenance: Provenance,
    ): Thought {
        require(thoughts.size < MAX_THOUGHTS) { "Thought library is full" }
        val privacyFixed = if (type == ThoughtType.CONVERSATION) ThoughtPrivacy.PRIVATE else privacy
        val now = provenance.atMillis
        val thought = Thought(ids(), type, title.trim(), contentRef, provenance, privacyFixed, coordinates, createdAt = now, updatedAt = now)
        record("create", thought.id, provenance, GraphDelta(removeThoughts = listOf(thought.id)), GraphDelta(thoughts = listOf(thought)))
        return requireThought(thought.id)
    }

    fun update(id: String, provenance: Provenance, change: (Thought) -> Thought): Thought {
        val old = requireThought(id)
        val changed = change(old)
        val next = changed.copy(id = old.id, createdAt = old.createdAt, updatedAt = maxOf(provenance.atMillis, old.createdAt))
        record("update", id, provenance, GraphDelta(thoughts = listOf(old)), GraphDelta(thoughts = listOf(next)))
        return requireThought(id)
    }

    fun delete(id: String, confirmed: Boolean, provenance: Provenance) {
        require(confirmed) { "Deleting a thought needs an explicit confirmation." }
        val old = requireThought(id)
        val related = links.values.filter { it.fromId == id || it.toId == id }
        val relatedAnchors = anchors.values.filter { it.thoughtId == id }
        record(
            "delete", id, provenance,
            GraphDelta(thoughts = listOf(old), links = related, anchors = relatedAnchors),
            GraphDelta(removeThoughts = listOf(id)),
        )
    }

    fun link(fromId: String, toId: String, relation: String, evidenceRef: String, provenance: Provenance): ThoughtLink {
        require(thoughts.size >= 0)
        requireThought(fromId); requireThought(toId)
        require(links.size < MAX_LINKS) { "Link library is full" }
        require(links.values.none { it.fromId == fromId && it.toId == toId && it.relation == relation }) { "That link already exists" }
        val link = ThoughtLink(ids(), fromId, toId, relation, evidenceRef.trim())
        record("link", link.id, provenance, GraphDelta(removeLinks = listOf(link.id)), GraphDelta(links = listOf(link)))
        return links.getValue(link.id)
    }

    fun unlink(linkId: String, provenance: Provenance) {
        val old = links[linkId] ?: error("Unknown link")
        record("unlink", linkId, provenance, GraphDelta(links = listOf(old)), GraphDelta(removeLinks = listOf(linkId)))
    }

    fun createCluster(name: String, anchor: Vec3, provenance: Provenance): ThoughtCluster {
        require(clusters.size < MAX_CLUSTERS) { "Cluster library is full" }
        val cluster = ThoughtCluster(ids(), name.trim(), anchor)
        record("cluster", cluster.id, provenance, GraphDelta(removeClusters = listOf(cluster.id)), GraphDelta(clusters = listOf(cluster)))
        return clusters.getValue(cluster.id)
    }

    fun anchor(thoughtId: String, roomId: String, position: Vec3, rotationWxyz: List<Float>, provenance: Provenance): RoomAnchor {
        requireThought(thoughtId)
        val next = RoomAnchor(thoughtId, roomId, position, rotationWxyz)
        val previous = anchors[next.key()]
        val inverse = if (previous == null) GraphDelta(removeAnchors = listOf(next.key())) else GraphDelta(anchors = listOf(previous))
        record("anchor", thoughtId, provenance, inverse, GraphDelta(anchors = listOf(next)))
        return anchors.getValue(next.key())
    }

    /** Anchors for the room that is recognized right now. A different room id is refused. */
    fun palace(recognizedRoomId: String, requestedRoomId: String): List<RoomAnchor> {
        require(canonicalUuid(recognizedRoomId) && recognizedRoomId == requestedRoomId) { "Room anchors stay in the room that was recognized." }
        return anchors.values.filter { it.roomId == recognizedRoomId }.sortedBy { it.thoughtId }
    }

    fun attachPanel(thoughtId: String, panelId: String, provenance: Provenance) = update(thoughtId, provenance) { it.copy(panelId = panelId) }

    fun move(id: String, coordinates: Vec3, provenance: Provenance): Thought {
        val old = requireThought(id)
        val next = old.copy(coordinates = coordinates, updatedAt = maxOf(provenance.atMillis, old.createdAt))
        record("move", id, provenance, GraphDelta(thoughts = listOf(old)), GraphDelta(thoughts = listOf(next)))
        return requireThought(id)
    }

    fun search(query: String): List<Thought> {
        require(query.length <= 120) { "Search is too long" }
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return thoughts()
        return thoughts.values.mapNotNull { thought ->
            val title = thought.title.lowercase()
            val content = thought.contentRef.lowercase()
            val type = thought.type.name.lowercase()
            val evidence = links.values.filter { it.fromId == thought.id || it.toId == thought.id }
                .joinToString(" ") { "${it.relation} ${it.evidenceRef}" }.lowercase()
            val rank = when {
                title == needle -> 0
                title.startsWith(needle) -> 1
                title.contains(needle) -> 2
                content.contains(needle) || type.contains(needle) -> 3
                evidence.contains(needle) -> 4
                else -> return@mapNotNull null
            }
            rank to thought
        }.sortedWith(compareBy({ it.first }, { it.second.title.lowercase() }, { it.second.id })).map { it.second }
    }

    fun neighbors(id: String): List<Thought> {
        requireThought(id)
        val ids = links.values.mapNotNull {
            when (id) {
                it.fromId -> it.toId
                it.toId -> it.fromId
                else -> null
            }
        }.distinct().sorted()
        return ids.map { requireThought(it) }
    }

    fun layoutConstellation(rootId: String): ConstellationLayout {
        val root = requireThought(rootId)
        val linked = neighbors(rootId)
        val shown = linked.take(MAX_CONSTELLATION - 1)
        val nodes = mutableListOf(ConstellationNode(root, root.coordinates, evidence = false, root = true))
        val count = shown.size
        shown.forEachIndexed { index, thought ->
            val angle = if (count == 1) 0.0 else -0.85 + (1.7 * index / (count - 1))
            val radius = 0.55f
            val position = Vec3(
                root.coordinates.x + (kotlin.math.sin(angle) * radius).toFloat(),
                root.coordinates.y,
                root.coordinates.z + 0.35f + ((1.0 - kotlin.math.cos(angle)) * 0.15).toFloat(),
            )
            val evidence = links.values.any { link ->
                setOf(link.fromId, link.toId) == setOf(rootId, thought.id) && (link.relation == "evidence" || link.evidenceRef.isNotBlank())
            }
            nodes += ConstellationNode(thought, position, evidence, root = false)
        }
        return ConstellationLayout(nodes, omitted = linked.size - shown.size)
    }

    fun commitConstellation(rootId: String, provenance: Provenance): ConstellationLayout {
        val layout = layoutConstellation(rootId)
        val previous = layout.nodes.map { requireThought(it.thought.id) }
        val moved = layout.nodes.map { node ->
            val current = requireThought(node.thought.id)
            current.copy(coordinates = node.position, updatedAt = maxOf(provenance.atMillis, current.createdAt))
        }
        record("constellation", rootId, provenance, GraphDelta(thoughts = previous), GraphDelta(thoughts = moved))
        val refreshed = layout.nodes.map { node -> node.copy(thought = requireThought(node.thought.id), position = requireThought(node.thought.id).coordinates) }
        return layout.copy(nodes = refreshed)
    }

    fun tossPreview(thoughtId: String, direction: Vec3, named: String?): TossPreview {
        require(direction.finite()) { "Invalid toss direction" }
        requireThought(thoughtId)
        require(clusters.isNotEmpty()) { "Create a named cluster before tossing a thought." }
        val cluster = if (named != null) {
            val matches = clusters.values.filter { it.name.equals(named.trim(), ignoreCase = true) }.sortedBy { it.id }
            require(matches.isNotEmpty()) { "No cluster is named $named" }
            matches.first()
        } else {
            require(direction.length() > 0.2f) { "Name a cluster or flick toward one." }
            val aim = requireThought(thoughtId).coordinates + direction.normalized() * 1.2f
            clusters.values.sortedWith(compareBy({ (it.anchor - aim).length() }, { it.name.lowercase() }, { it.id })).first()
        }
        return TossPreview(thoughtId, cluster.id, cluster.name, cluster.anchor + stableOffset(thoughtId), direction)
    }

    /** Preview a specific cluster, including when two clusters share a name. */
    fun tossOnto(thoughtId: String, clusterId: String): TossPreview {
        requireThought(thoughtId)
        val cluster = clusters[clusterId] ?: error("Unknown cluster")
        return TossPreview(thoughtId, cluster.id, cluster.name, cluster.anchor + stableOffset(thoughtId), Vec3(0f, 0f, 0f))
    }

    /** Moves a thought onto a cluster. The thought, its links, and its text stay. */
    fun commitToss(preview: TossPreview, provenance: Provenance): Thought {
        val cluster = clusters[preview.clusterId] ?: error("Unknown cluster")
        require(cluster.name == preview.clusterName && preview.landing == cluster.anchor + stableOffset(preview.thoughtId)) { "Toss preview is stale" }
        val old = requireThought(preview.thoughtId)
        val next = old.copy(clusterId = preview.clusterId, coordinates = preview.landing, updatedAt = maxOf(provenance.atMillis, old.createdAt))
        record("toss", old.id, provenance, GraphDelta(thoughts = listOf(old)), GraphDelta(thoughts = listOf(next)))
        return requireThought(old.id)
    }

    fun planFocus(rootId: String, cards: Map<String, Boolean>, windows: Map<String, Boolean>): FocusBubble {
        requireThought(rootId)
        val visible = (neighbors(rootId).map { it.id } + rootId).toSet()
        val represented = visible.mapNotNull { thoughts[it]?.panelId }.toSet()
        return FocusBubble(
            rootId,
            visible,
            cards.toMap(),
            windows.toMap(),
            cards.mapValues { (id, _) -> id in visible },
            windows.mapValues { (id, _) -> id in represented },
        )
    }

    fun importThought(thought: Thought, provenance: Provenance): Thought {
        require(canShare(thought)) { "That object cannot be imported." }
        val existing = thoughts[thought.id]
        if (existing != null && !canShare(existing)) return existing
        val stored = thought.copy(provenance = provenance, privacy = ThoughtPrivacy.SHAREABLE, panelId = null, clusterId = null, updatedAt = maxOf(provenance.atMillis, thought.createdAt))
        if (existing == null) {
            require(thoughts.size < MAX_THOUGHTS) { "Thought library is full" }
            record("import", stored.id, provenance, GraphDelta(removeThoughts = listOf(stored.id)), GraphDelta(thoughts = listOf(stored)))
        } else {
            record("import", stored.id, provenance, GraphDelta(thoughts = listOf(existing)), GraphDelta(thoughts = listOf(stored)))
        }
        return requireThought(stored.id)
    }

    fun importLink(link: ThoughtLink, provenance: Provenance): ThoughtLink? {
        if (thoughts[link.fromId] == null || thoughts[link.toId] == null) return null
        links[link.id]?.let { return it }
        require(links.size < MAX_LINKS) { "Link library is full" }
        val stored = ThoughtLink(link.id, link.fromId, link.toId, link.relation, link.evidenceRef)
        record("import", stored.id, provenance, GraphDelta(removeLinks = listOf(stored.id)), GraphDelta(links = listOf(stored)))
        return links[stored.id]
    }

    fun undo(actor: String = "local"): ThoughtEvent? {
        val pending = ArrayDeque<ThoughtEvent>()
        events.forEach { event -> if (event.kind == "undo") { if (pending.isNotEmpty()) pending.removeLast() } else pending.addLast(event) }
        val target = pending.lastOrNull() ?: return null
        val provenance = Provenance("system", actor, maxOf(clock(), 0L), "undo")
        return record("undo", target.subjectId, provenance, target.forward, target.inverse)
    }

    /** Library as it was after [count] stored events, including undos. */
    fun stateAt(count: Int): BrainSnapshot {
        require(count in 0..events.size) { "History index is outside the stored events" }
        val replay = ThoughtGraph(clock, ids)
        events.take(count).forEach { replay.apply(it.forward) }
        return replay.snapshot()
    }

    private fun record(kind: String, subjectId: String, provenance: Provenance, inverse: GraphDelta, forward: GraphDelta): ThoughtEvent {
        require(events.size < MAX_EVENTS) { "Thought history is full" }
        apply(forward)
        val event = ThoughtEvent(ids(), provenance.atMillis, kind, subjectId, provenance, inverse, forward)
        events += event
        check(snapshot() == stateAt(events.size)) { "History diverged from the library" }
        return event
    }

    private fun apply(delta: GraphDelta) {
        delta.removeLinks.forEach { links.remove(it) }
        delta.removeAnchors.forEach { anchors.remove(it) }
        delta.removeClusters.forEach { clusters.remove(it) }
        delta.removeThoughts.forEach { id ->
            thoughts.remove(id)
            links.entries.removeAll { it.value.fromId == id || it.value.toId == id }
            anchors.entries.removeAll { it.value.thoughtId == id }
        }
        delta.clusters.forEach { clusters[it.id] = it }
        delta.thoughts.forEach { thoughts[it.id] = it }
        delta.links.forEach { link ->
            require(thoughts.containsKey(link.fromId) && thoughts.containsKey(link.toId)) { "Link is missing a thought" }
            links[link.id] = link
        }
        delta.anchors.forEach { anchor ->
            require(thoughts.containsKey(anchor.thoughtId)) { "Anchor is missing a thought" }
            anchors[anchor.key()] = anchor
        }
    }

    companion object {
        const val MAX_THOUGHTS = 256
        const val MAX_LINKS = 512
        const val MAX_CLUSTERS = 32
        const val MAX_CONSTELLATION = 12
        const val MAX_VISIBLE_CARDS = MAX_CONSTELLATION
        const val MAX_EVENTS = 1000
        const val MAX_BYTES = 1_500_000

        fun encode(graph: ThoughtGraph): String {
            val root = JSONObject()
                .put("version", 1)
                .put("thoughts", JSONArray().apply { graph.thoughts.values.forEach { put(thoughtJson(it)) } })
                .put("links", JSONArray().apply { graph.links.values.forEach { put(linkJson(it)) } })
                .put("clusters", JSONArray().apply { graph.clusters.values.forEach { put(clusterJson(it)) } })
                .put("anchors", JSONArray().apply { graph.anchors.values.forEach { put(anchorJson(it)) } })
                .put("events", JSONArray().apply { graph.events.forEach { put(eventJson(it)) } })
            val json = root.toString()
            decode(json, graph.clock, graph.ids)
            return json
        }

        fun decode(json: String, clock: () -> Long = { System.currentTimeMillis() }, ids: () -> String = { UUID.randomUUID().toString() }): ThoughtGraph {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Thought library is too large" }
            val root = JSONObject(json)
            require(root.getInt("version") == 1) { "Unsupported thought library version" }
            val graph = ThoughtGraph(clock, ids)
            val parsedEvents = root.getJSONArray("events")
            require(parsedEvents.length() <= MAX_EVENTS) { "Thought history is too long" }
            for (index in 0 until parsedEvents.length()) graph.apply(event(parsedEvents.getJSONObject(index)).also { graph.events += it }.forward)
            val materialized = BrainSnapshot(
                jsonThoughts(root.getJSONArray("thoughts")).sortedWith(compareBy({ it.title.lowercase() }, { it.id })),
                jsonLinks(root.getJSONArray("links")).sortedBy { it.id },
                jsonClusters(root.getJSONArray("clusters")).sortedWith(compareBy({ it.name.lowercase() }, { it.id })),
                jsonAnchors(root.getJSONArray("anchors")).sortedWith(compareBy({ it.roomId }, { it.thoughtId })),
            )
            require(graph.snapshot() == materialized) { "History does not match the stored thoughts" }
            require(graph.thoughts.size <= MAX_THOUGHTS && graph.links.size <= MAX_LINKS && graph.clusters.size <= MAX_CLUSTERS)
            return graph
        }
    }
}

fun canonicalUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

fun anchorKey(thoughtId: String, roomId: String) = "$thoughtId|$roomId"

fun stableOffset(id: String): Vec3 {
    val hash = id.hashCode()
    val x = ((hash and 255) - 128) / 1280f
    val z = (((hash ushr 8) and 255) - 128) / 1280f
    return Vec3(x, 0f, z)
}

fun canShare(thought: Thought): Boolean {
    if (thought.type == ThoughtType.CONVERSATION || thought.privacy != ThoughtPrivacy.SHAREABLE) return false
    val ref = thought.contentRef.lowercase()
    if (ref.contains("cookie") || ref.startsWith("camera:") || ref.startsWith("camera-stream:") || ref.startsWith("local-snapshot:")) return false
    if (RoomLayoutCodec.safeUrl(thought.contentRef)) return false
    return true
}

private fun thoughtJson(thought: Thought) = JSONObject()
    .put("id", thought.id).put("type", thought.type.name).put("title", thought.title).put("contentRef", thought.contentRef)
    .put("provenance", provenanceJson(thought.provenance)).put("privacy", thought.privacy.name)
    .put("coordinates", vecJson(thought.coordinates))
    .put("panelId", thought.panelId ?: JSONObject.NULL).put("clusterId", thought.clusterId ?: JSONObject.NULL)
    .put("createdAt", thought.createdAt).put("updatedAt", thought.updatedAt)

private fun linkJson(link: ThoughtLink) = JSONObject()
    .put("id", link.id).put("fromId", link.fromId).put("toId", link.toId).put("relation", link.relation).put("evidenceRef", link.evidenceRef)

private fun clusterJson(cluster: ThoughtCluster) = JSONObject().put("id", cluster.id).put("name", cluster.name).put("anchor", vecJson(cluster.anchor))

private fun anchorJson(anchor: RoomAnchor) = JSONObject()
    .put("thoughtId", anchor.thoughtId).put("roomId", anchor.roomId).put("position", vecJson(anchor.position)).put("rotationWxyz", JSONArray(anchor.rotationWxyz))

private fun provenanceJson(provenance: Provenance) = JSONObject()
    .put("source", provenance.source).put("actor", provenance.actor).put("atMillis", provenance.atMillis).put("note", provenance.note)

private fun deltaJson(delta: GraphDelta) = JSONObject()
    .put("thoughts", JSONArray().apply { delta.thoughts.forEach { put(thoughtJson(it)) } })
    .put("removeThoughts", JSONArray(delta.removeThoughts))
    .put("links", JSONArray().apply { delta.links.forEach { put(linkJson(it)) } })
    .put("removeLinks", JSONArray(delta.removeLinks))
    .put("clusters", JSONArray().apply { delta.clusters.forEach { put(clusterJson(it)) } })
    .put("removeClusters", JSONArray(delta.removeClusters))
    .put("anchors", JSONArray().apply { delta.anchors.forEach { put(anchorJson(it)) } })
    .put("removeAnchors", JSONArray(delta.removeAnchors))

private fun eventJson(event: ThoughtEvent) = JSONObject()
    .put("id", event.id).put("atMillis", event.atMillis).put("kind", event.kind).put("subjectId", event.subjectId)
    .put("provenance", provenanceJson(event.provenance)).put("inverse", deltaJson(event.inverse)).put("forward", deltaJson(event.forward))

private fun vecJson(value: Vec3) = JSONArray().put(value.x.toDouble()).put(value.y.toDouble()).put(value.z.toDouble())

private fun jsonVec(array: JSONArray): Vec3 {
    require(array.length() == 3) { "Invalid vector" }
    return Vec3(array.getDouble(0).toFloat(), array.getDouble(1).toFloat(), array.getDouble(2).toFloat())
}

private fun jsonThoughts(array: JSONArray) = List(array.length()) { thought(array.getJSONObject(it)) }
private fun jsonLinks(array: JSONArray) = List(array.length()) { link(array.getJSONObject(it)) }
private fun jsonClusters(array: JSONArray) = List(array.length()) { cluster(array.getJSONObject(it)) }
private fun jsonAnchors(array: JSONArray) = List(array.length()) { anchor(array.getJSONObject(it)) }

private fun thought(json: JSONObject): Thought {
    val panel = json.opt("panelId")
    val cluster = json.opt("clusterId")
    return Thought(
        json.getString("id"),
        ThoughtType.valueOf(json.getString("type")),
        json.getString("title"),
        json.getString("contentRef"),
        provenance(json.getJSONObject("provenance")),
        ThoughtPrivacy.valueOf(json.getString("privacy")),
        jsonVec(json.getJSONArray("coordinates")),
        if (panel == null || panel == JSONObject.NULL) null else panel.toString(),
        if (cluster == null || cluster == JSONObject.NULL) null else cluster.toString(),
        json.getLong("createdAt"),
        json.getLong("updatedAt"),
    )
}

private fun link(json: JSONObject) = ThoughtLink(json.getString("id"), json.getString("fromId"), json.getString("toId"), json.getString("relation"), json.optString("evidenceRef", ""))

private fun cluster(json: JSONObject) = ThoughtCluster(json.getString("id"), json.getString("name"), jsonVec(json.getJSONArray("anchor")))

private fun anchor(json: JSONObject): RoomAnchor {
    val rotation = json.getJSONArray("rotationWxyz")
    require(rotation.length() == 4) { "Invalid anchor rotation" }
    return RoomAnchor(json.getString("thoughtId"), json.getString("roomId"), jsonVec(json.getJSONArray("position")), List(4) { rotation.getDouble(it).toFloat() })
}

private fun provenance(json: JSONObject) = Provenance(json.getString("source"), json.getString("actor"), json.getLong("atMillis"), json.optString("note", ""))

private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }

private fun delta(json: JSONObject) = GraphDelta(
    jsonThoughts(json.optJSONArray("thoughts") ?: JSONArray()),
    strings(json.optJSONArray("removeThoughts") ?: JSONArray()),
    jsonLinks(json.optJSONArray("links") ?: JSONArray()),
    strings(json.optJSONArray("removeLinks") ?: JSONArray()),
    jsonClusters(json.optJSONArray("clusters") ?: JSONArray()),
    strings(json.optJSONArray("removeClusters") ?: JSONArray()),
    jsonAnchors(json.optJSONArray("anchors") ?: JSONArray()),
    strings(json.optJSONArray("removeAnchors") ?: JSONArray()),
)

private fun event(json: JSONObject) = ThoughtEvent(
    json.getString("id"), json.getLong("atMillis"), json.getString("kind"), json.getString("subjectId"),
    provenance(json.getJSONObject("provenance")), delta(json.getJSONObject("inverse")), delta(json.getJSONObject("forward")),
)
