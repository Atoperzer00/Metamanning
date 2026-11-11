package com.metamanning.questlite

import java.util.ArrayDeque
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class EnvironmentManifest(val id: String, val license: String, val bundled: Boolean, val marketplaceUrl: String = "")

data class EnvironmentSelection(val active: EnvironmentPackage, val lod: LodLevel?, val fallback: Boolean, val reason: String)

object EnvironmentCatalog {
    const val FRAME_BUDGET_MS = 13.9f
    val PASSTHROUGH = EnvironmentPackage(
        "passthrough", "Passthrough", "room",
        "Device camera composite. No imported environment assets.",
        "device-composite", emptyList(), emptyList(), emptyList(), FRAME_BUDGET_MS, true, "#0B1019", "#152536",
    )
    private val installer = EnvironmentInstaller(CuratedChannel)

    fun curated() = listOf(PASSTHROUGH, CuratedChannel.definition("observatory"), CuratedChannel.definition("design-studio"))

    val OBSERVATORY get() = CuratedChannel.definition("observatory")
    val DESIGN_STUDIO get() = CuratedChannel.definition("design-studio")

    fun select(id: String, measuredFrameMs: Float?) = installer.activate(id, measuredFrameMs)

    fun accept(manifest: EnvironmentManifest): EnvironmentPackage {
        require(manifest.marketplaceUrl.isBlank() && !manifest.id.contains("://")) { "No environment marketplace" }
        require(manifest.bundled && manifest.license.isNotBlank()) { "Environment is missing a license" }
        val known = CuratedChannel.definition(manifest.id)
        require(known.license == manifest.license) { "Environment license does not match the curated package" }
        require(known.lod.size >= 2 && known.lights.isNotEmpty() && known.occlusion.isNotEmpty()) { "Environment package is incomplete" }
        return known
    }
}

class FrameBudget(private val budgetMs: Float = EnvironmentCatalog.FRAME_BUDGET_MS, private val samplesNeeded: Int = 12) {
    private val samples = ArrayDeque<Float>()
    fun add(frameMs: Float) {
        if (!frameMs.isFinite() || frameMs < 0f) return
        samples.addLast(frameMs)
        while (samples.size > samplesNeeded) samples.removeFirst()
    }
    fun ready() = samples.size >= samplesNeeded
    fun average() = if (samples.isEmpty()) 0f else samples.sum() / samples.size
    fun exceeded() = ready() && average() > budgetMs
}

enum class SessionRole { HOST, GUEST }

data class SyncEnvelope(val sessionId: String, val role: SessionRole, val thoughts: List<Thought>, val links: List<ThoughtLink>) {
    init {
        require(canonicalUuid(sessionId)) { "Invalid co-think session" }
        require(role == SessionRole.HOST) { "A share envelope comes from the host." }
        require(thoughts.all { canShare(it) }) { "Envelope contains a private object" }
        require(thoughts.map { it.id }.distinct().size == thoughts.size) { "Duplicate shared thought" }
    }
}

class CoThinkSession(val id: String, val role: SessionRole) {
    var approvedIds: Set<String> = emptySet()
        private set

    init { require(canonicalUuid(id)) { "Invalid co-think session" } }

    fun approve(graph: ThoughtGraph, ids: List<String>) {
        require(role == SessionRole.HOST) { "Only the host approves what is shared." }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Approve specific thoughts." }
        ids.forEach { id -> require(canShare(graph.requireThought(id))) { "Private conversations, camera, and cookies stay on this headset." } }
        approvedIds = ids.toSet()
    }

    fun envelope(graph: ThoughtGraph): SyncEnvelope {
        require(role == SessionRole.HOST && approvedIds.isNotEmpty()) { "Approve thoughts before preparing a share." }
        val shared = approvedIds.map { graph.requireThought(it) }
        val links = graph.links().filter { it.fromId in approvedIds && it.toId in approvedIds }
        return SyncEnvelope(id, SessionRole.HOST, shared, links)
    }
}

fun importEnvelope(graph: ThoughtGraph, envelope: SyncEnvelope, provenance: Provenance): List<Thought> {
    val imported = envelope.thoughts.map { graph.importThought(it, provenance) }
    envelope.links.forEach { graph.importLink(it, provenance) }
    return imported
}

fun encodeEnvelope(envelope: SyncEnvelope): String {
    val root = JSONObject()
        .put("version", 1)
        .put("sessionId", envelope.sessionId)
        .put("role", envelope.role.name)
        .put("thoughts", JSONArray().apply { envelope.thoughts.forEach { put(sharedThoughtJson(it)) } })
        .put("links", JSONArray().apply { envelope.links.forEach { put(sharedLinkJson(it)) } })
    return root.toString().also { decodeEnvelope(it) }
}

fun decodeEnvelope(json: String): SyncEnvelope {
    require(json.toByteArray(Charsets.UTF_8).size <= 256_000) { "Share envelope is too large" }
    val root = JSONObject(json)
    require(root.getInt("version") == 1) { "Unsupported share envelope" }
    listOf("anchors", "windows", "cookies", "camera", "events").forEach { require(!root.has(it)) { "Share envelope carries $it" } }
    val thoughts = root.getJSONArray("thoughts")
    val links = root.getJSONArray("links")
    return SyncEnvelope(
        root.getString("sessionId"),
        SessionRole.valueOf(root.getString("role")),
        List(thoughts.length()) { sharedThought(thoughts.getJSONObject(it)) },
        List(links.length()) { sharedLink(links.getJSONObject(it)) },
    )
}

enum class CapturePhase { IDLE, ARMED, CAPTURED, SUBMITTED }

data class LocalSnapshot(val objectId: String, val label: String, val sha256: String, val byteLength: Int)

data class ReviewedBridgeRequest(
    val id: String,
    val prompt: String,
    val objectId: String,
    val objectLabel: String,
    val snapshotRef: String,
    val claimsWebsiteToolAccess: Boolean,
) {
    init {
        require(canonicalUuid(id) && canonicalUuid(objectId)) { "Invalid bridge request" }
        require(!claimsWebsiteToolAccess) { "The website does not grant native tool access" }
        require(prompt.isNotBlank() && prompt.length <= 500) { "Invalid look-and-ask prompt" }
        require(objectLabel.isNotBlank() && objectLabel.length <= 60 && objectLabel.none { it.code < 32 }) { "Invalid captured object" }
        require(snapshotRef.startsWith("local-snapshot:") && snapshotRef.length <= 80 && !snapshotRef.contains("http")) { "Invalid local snapshot" }
    }
}

/** Opt-in capture of one selected object. The bytes stay on this headset; only a hash is kept. */
class LookAndAsk(private val ids: () -> String = { UUID.randomUUID().toString() }) {
    var optedIn: Boolean = false
        private set
    var phase: CapturePhase = CapturePhase.IDLE
        private set
    var indicatorVisible: Boolean = false
        private set
    var pending: ReviewedBridgeRequest? = null
        private set
    var snapshot: LocalSnapshot? = null
        private set

    fun optIn() { optedIn = true }

    fun arm(): String {
        require(optedIn) { "Look and ask is opt-in." }
        phase = CapturePhase.ARMED
        indicatorVisible = true
        snapshot = null
        pending = null
        return "CAPTURE ON. Nothing has been saved or sent."
    }

    fun captureObject(objectId: String, label: String, bytes: ByteArray) {
        require(phase == CapturePhase.ARMED && indicatorVisible) { "Turn the capture indicator on first." }
        require(canonicalUuid(objectId)) { "Select an object before capturing." }
        require(label.isNotBlank() && label.length <= 60 && label.none { it.code < 32 }) { "Invalid captured object" }
        require(bytes.isNotEmpty() && bytes.size <= 256_000) { "Snapshot must stay a local reference" }
        val digest = sha256Hex(bytes)
        snapshot = LocalSnapshot(objectId, label, digest, bytes.size)
        phase = CapturePhase.CAPTURED
    }

    fun submit(prompt: String): ReviewedBridgeRequest {
        require(phase == CapturePhase.CAPTURED) { "Keep a snapshot before submitting." }
        val kept = requireNotNull(snapshot)
        val request = ReviewedBridgeRequest(
            ids(), prompt.trim(), kept.objectId, kept.label, "local-snapshot:${kept.sha256}", claimsWebsiteToolAccess = false,
        )
        pending = request
        phase = CapturePhase.SUBMITTED
        indicatorVisible = false
        return request
    }

    fun cancel() {
        snapshot = null
        pending = null
        phase = CapturePhase.IDLE
        indicatorVisible = false
    }
}

data class BenchItem(val thoughtId: String, val kind: String, val ref: String, val network: Boolean, val html: String)

object BuildBench {
    fun materials(graph: ThoughtGraph, ids: List<String>): List<BenchItem> {
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Choose thoughts for the bench." }
        return ids.map { id ->
            val thought = graph.requireThought(id)
            when {
                thought.type == ThoughtType.CONVERSATION || RoomLayoutCodec.safeUrl(thought.contentRef) ->
                    BenchItem(id, "assistant", "", false, "")
                thought.contentRef.startsWith("https://", ignoreCase = true) -> {
                    require(WebAddress.allowedHttps(thought.contentRef)) { "Browser material must be an allowed HTTPS address." }
                    BenchItem(id, "browser", thought.contentRef, false, "")
                }
                thought.contentRef.startsWith("javascript:", ignoreCase = true) ||
                    thought.contentRef.startsWith("http:", ignoreCase = true) ||
                    thought.contentRef.startsWith("file:", ignoreCase = true) ||
                    thought.contentRef.startsWith("content:", ignoreCase = true) ||
                    thought.contentRef.startsWith("data:", ignoreCase = true) ->
                    error("Browser material must be an allowed HTTPS address.")
                else -> BenchItem(id, "widget", "", false, offlineHtml(thought))
            }
        }
    }

    fun offlineHtml(thought: Thought) = "<h1>${escapeHtml(thought.title)}</h1><p>${escapeHtml(thought.contentRef)}</p>"
}

fun escapeHtml(value: String) = buildString {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(char)
        }
    }
}

object Retrieval {
    fun prompt(query: String, thoughts: List<Thought>): String {
        require(query.isNotBlank() && query.length <= 200) { "Retrieval question is invalid" }
        val lines = thoughts.take(5).joinToString("\n") { thought ->
            val ref = if (thought.type == ThoughtType.CONVERSATION || RoomLayoutCodec.safeUrl(thought.contentRef)) {
                "(conversation stays in the assistant window)"
            } else thought.contentRef.take(120)
            "- ${thought.title} [${thought.type.name.lowercase()}] $ref"
        }
        val notes = if (lines.isBlank()) "No matching local notes." else lines
        return "Using my local notes, answer this: ${query.trim()}\n\nNotes:\n$notes\n\nPrepared on device. This does not call ChatGPT tools. Send only if you want this in the conversation."
    }
}

class SecondBrain(private val clock: () -> Long = { System.currentTimeMillis() }) {
    var graph: ThoughtGraph = ThoughtGraph(clock)
    val gestures = GestureMachine()
    val look = LookAndAsk()
    val frames = FrameBudget()
    var environmentId: String = "passthrough"
    var environmentLod: Int? = null
    var focus: FocusBubble? = null
    var share: CoThinkSession? = null
    var selectedId: String? = null

    fun loadJson(json: String) {
        graph = ThoughtGraph.decode(json, clock)
        if (selectedId != null && graph.find(selectedId!!) == null) selectedId = null
    }
}

private fun sharedThoughtJson(thought: Thought) = JSONObject()
    .put("id", thought.id).put("type", thought.type.name).put("title", thought.title).put("contentRef", thought.contentRef)
    .put("provenance", JSONObject().put("source", thought.provenance.source).put("actor", thought.provenance.actor).put("atMillis", thought.provenance.atMillis).put("note", thought.provenance.note))
    .put("privacy", thought.privacy.name).put("coordinates", JSONArray().put(thought.coordinates.x.toDouble()).put(thought.coordinates.y.toDouble()).put(thought.coordinates.z.toDouble()))
    .put("createdAt", thought.createdAt).put("updatedAt", thought.updatedAt)

private fun sharedLinkJson(link: ThoughtLink) = JSONObject()
    .put("id", link.id).put("fromId", link.fromId).put("toId", link.toId).put("relation", link.relation).put("evidenceRef", link.evidenceRef)

private fun sharedThought(json: JSONObject) = Thought(
    json.getString("id"), ThoughtType.valueOf(json.getString("type")), json.getString("title"), json.getString("contentRef"),
    Provenance(json.getJSONObject("provenance").getString("source"), json.getJSONObject("provenance").getString("actor"), json.getJSONObject("provenance").getLong("atMillis"), json.getJSONObject("provenance").optString("note")),
    ThoughtPrivacy.valueOf(json.getString("privacy")),
    Vec3(json.getJSONArray("coordinates").getDouble(0).toFloat(), json.getJSONArray("coordinates").getDouble(1).toFloat(), json.getJSONArray("coordinates").getDouble(2).toFloat()),
    createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
)

private fun sharedLink(json: JSONObject) = ThoughtLink(json.getString("id"), json.getString("fromId"), json.getString("toId"), json.getString("relation"), json.optString("evidenceRef"))
