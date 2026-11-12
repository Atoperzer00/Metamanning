package com.metamanning.questlite

import kotlin.math.PI
import kotlin.math.sin

/** What the scene is playing. Head and hand rays are not a motion source. */
enum class MotionKind { SLING, TOSS, EXPAND, COLLAPSE, FOCUS, RESTORE }

data class CardTrack(
    val id: String,
    val from: Vec3,
    val to: Vec3,
    val fromScale: Float = 1f,
    val toScale: Float = 1f,
    val fromDim: Float = 0f,
    val toDim: Float = 0f,
) {
    init {
        require(id.isNotBlank()) { "A moving thought needs an id" }
        require(from.finite() && to.finite()) { "Motion left the library frame" }
        require(listOf(fromScale, toScale).all { it.isFinite() && it in 0.05f..4f }) { "Motion scale is out of range" }
        require(listOf(fromDim, toDim).all { it.isFinite() && it in 0f..1f }) { "Motion dim is out of range" }
    }
}

data class CardSample(val id: String, val position: Vec3, val scale: Float, val dim: Float)

data class TetherSample(val origin: Vec3, val tip: Vec3, val beads: List<Vec3>)

data class MotionFrame(
    val kind: MotionKind,
    val progress: Float,
    val cards: List<CardSample>,
    val tether: TetherSample?,
    val landing: Vec3?,
    val arrived: Boolean,
    val justArrived: Boolean,
    val holding: Boolean,
    val returning: Boolean,
)

/**
 * Library frame shared with [reading pose][PullPolicy.DISTANCE_M].
 * x is meters to the right, y is meters above the reading height, z is extra distance past the reading plane.
 */
object ReadingFrame {
    const val EYE_DROP_M = 0.12f

    fun library(localX: Float, localY: Float, localZ: Float) =
        Vec3(localX, localY + EYE_DROP_M, localZ - PullPolicy.DISTANCE_M)

    fun local(coordinates: Vec3) =
        Vec3(coordinates.x, coordinates.y - EYE_DROP_M, PullPolicy.DISTANCE_M + coordinates.z)
}

/** Paths the scene can play without a headset. Positions stay in the library frame. */
object ThoughtPaths {
    const val BEADS = 8
    const val SAG_M = 0.12f

    fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun mix(from: Float, to: Float, t: Float) = from + (to - from) * smooth(t)

    fun mix(from: Vec3, to: Vec3, t: Float) = from + (to - from) * smooth(t)

    fun hopHeight(from: Vec3, to: Vec3) = ((to - from).length() * 0.28f).coerceIn(0.16f, 0.55f)

    /** Toss arc. The ends sit on the chord so commit does not pop the card. */
    fun arc(from: Vec3, to: Vec3, t: Float): Vec3 {
        if (t <= 0f) return from
        if (t >= 1f) return to
        val hop = sin(PI.toFloat() * t) * hopHeight(from, to)
        return mix(from, to, t) + Vec3(0f, hop, 0f)
    }

    fun beads(origin: Vec3, tip: Vec3, count: Int = BEADS): List<Vec3> {
        require(count in 2..24) { "Tether bead count is out of range" }
        return List(count) { index ->
            val u = (index + 1f) / (count + 1f)
            val point = origin + (tip - origin) * u
            point + Vec3(0f, -4f * u * (1f - u) * SAG_M, 0f)
        }
    }

    fun expand(rootId: String, rootAt: Vec3, nodes: List<Pair<String, Vec3>>, openAt: Map<String, Vec3>): List<CardTrack> {
        require(nodes.any { it.first == rootId }) { "A constellation needs its root" }
        return nodes.map { (id, target) ->
            val from = if (id == rootId) rootAt else openAt[id] ?: rootAt
            CardTrack(id, from, target)
        }
    }

    fun collapse(rootId: String, rootAt: Vec3, openAt: List<Pair<String, Vec3>>): List<CardTrack> {
        require(openAt.any { it.first == rootId }) { "Collapse needs the card that stays" }
        return openAt.map { (id, at) ->
            val child = id != rootId
            CardTrack(id, at, rootAt, 1f, if (child) 0.12f else 1f, 0f, if (child) 0.85f else 0f)
        }
    }

    fun focus(homes: List<Pair<String, Vec3>>, rootId: String, members: Set<String>, anchored: Set<String> = emptySet()): List<CardTrack> {
        require(rootId in members && homes.any { it.first == rootId }) { "Focus needs the idea that stays bright" }
        val home = homes.toMap()
        val gathered = gather(rootId, home.getValue(rootId), members.filter { it in home })
        return homes.map { (id, at) ->
            when {
                id in members -> CardTrack(id, at, gathered.getValue(id))
                id in anchored -> CardTrack(id, at, at, 1f, 0.42f, 0f, 1f)
                else -> CardTrack(id, at, at + Vec3(0f, -0.16f, 0.48f), 1f, 0.42f, 0f, 1f)
            }
        }
    }

    fun gather(rootId: String, root: Vec3, memberIds: List<String>): Map<String, Vec3> {
        val others = memberIds.filter { it != rootId }.distinct().sorted()
        val gap = 0.46f
        val span = (others.size - 1) * gap
        val seats = others.mapIndexed { index, id ->
            val x = if (others.size <= 1) gap else -span / 2f + index * gap
            id to Vec3(root.x + x, root.y, root.z)
        }.toMap()
        val rootSeat = if (others.isEmpty() && root.z > 0.2f) Vec3(root.x, root.y, 0.05f) else root
        return seats + (rootId to rootSeat)
    }

    fun restore(tracks: List<CardTrack>) = tracks.map { track ->
        CardTrack(track.id, track.to, track.from, track.toScale, track.fromScale, track.toDim, track.fromDim)
    }
}

/**
 * Plays one scene motion at a time.
 * Sling and toss hold at the landing until commit or cancel. Expand, collapse, focus, and restore finish on their own.
 */
class ThoughtMotion {
    var kind: MotionKind? = null
        private set
    var subjectId: String? = null
        private set
    var arrived: Boolean = false
        private set
    var returning: Boolean = false
        private set
    var holding: Boolean = false
        private set
    val active: Boolean get() = kind != null

    private var tracks = emptyList<CardTrack>()
    private var elapsed = 0f
    private var duration = 0.8f
    private var slingTime = 0f
    private var announced = false
    private var spring: SpringBody? = null
    private var springTarget: Vec3? = null
    private var tetherOrigin: Vec3? = null

    fun controls(id: String) = active && tracks.any { it.id == id }

    fun sampleOf(id: String) = if (controls(id)) snapshot(false).cards.firstOrNull { it.id == id } else null

    fun frame() = if (active) snapshot(false) else null

    fun beginSling(id: String, from: Vec3, to: Vec3, origin: Vec3) {
        require(origin.finite()) { "Tether origin is not a place" }
        requireIdle()
        kind = MotionKind.SLING
        subjectId = id
        holding = true
        tracks = listOf(CardTrack(id, from, to))
        spring = SpringBody(from, Vec3(0f, 0f, 0f))
        springTarget = to
        tetherOrigin = origin
    }

    fun beginToss(id: String, from: Vec3, landing: Vec3) {
        requireIdle()
        kind = MotionKind.TOSS
        subjectId = id
        holding = true
        duration = 0.95f
        tracks = listOf(CardTrack(id, from, landing))
    }

    fun beginTravel(kind: MotionKind, tracks: List<CardTrack>, subjectId: String) {
        require(kind != MotionKind.SLING && kind != MotionKind.TOSS) { "Travel is for expand, collapse, focus, and restore" }
        require(tracks.isNotEmpty() && tracks.size <= ThoughtGraph.MAX_VISIBLE_CARDS) { "Travel needs the open cards" }
        require(tracks.any { it.id == subjectId }) { "Travel needs its subject" }
        requireIdle()
        this.kind = kind
        this.subjectId = subjectId
        holding = false
        duration = when (kind) {
            MotionKind.EXPAND -> 0.85f
            MotionKind.COLLAPSE -> 0.72f
            MotionKind.FOCUS -> 0.8f
            else -> 0.75f
        }
        this.tracks = tracks.toList()
    }

    fun step(dtSeconds: Float): MotionFrame? {
        if (!active) return null
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        if (kind == MotionKind.SLING) {
            spring = TetherSpring.step(requireNotNull(spring), requireNotNull(springTarget), dt.coerceAtLeast(0.001f))
            slingTime += dt
        } else elapsed += dt
        return snapshot(true)
    }

    fun reverse() {
        if (!active || returning) return
        val now = snapshot(false).cards.associateBy { it.id }
        if (kind == MotionKind.SLING) {
            val home = tracks.first().from
            spring = SpringBody(now.getValue(tracks.first().id).position, Vec3(0f, 0f, 0f))
            springTarget = home
            slingTime = 0f
        } else {
            tracks = tracks.map { track ->
                val pose = now.getValue(track.id)
                track.copy(
                    from = pose.position,
                    to = track.from,
                    fromScale = pose.scale,
                    toScale = track.fromScale,
                    fromDim = pose.dim,
                    toDim = track.fromDim,
                )
            }
            elapsed = 0f
        }
        returning = true
        holding = false
        announced = false
        arrived = false
    }

    fun stop() {
        kind = null
        subjectId = null
        tracks = emptyList()
        elapsed = 0f
        slingTime = 0f
        announced = false
        arrived = false
        returning = false
        holding = false
        spring = null
        springTarget = null
        tetherOrigin = null
    }

    private fun requireIdle() {
        require(!active) { "A motion is already playing" }
    }

    private fun travelT() = if (duration <= 0f) 1f else (elapsed / duration).coerceIn(0f, 1f)

    private fun sample(track: CardTrack, t: Float): CardSample {
        val position = when (kind) {
            MotionKind.SLING -> spring?.position ?: track.from
            MotionKind.TOSS -> ThoughtPaths.arc(track.from, track.to, t)
            else -> ThoughtPaths.mix(track.from, track.to, t)
        }
        val scale = if (kind == MotionKind.SLING) 1f else ThoughtPaths.mix(track.fromScale, track.toScale, t)
        val dim = if (kind == MotionKind.SLING) 0f else ThoughtPaths.mix(track.fromDim, track.toDim, t)
        return CardSample(track.id, position, scale, dim)
    }

    private fun isArrived(): Boolean = when (kind) {
        MotionKind.SLING -> {
            val body = spring ?: return false
            val target = springTarget ?: return false
            springSettled(body, target) || slingTime >= 3.5f
        }
        else -> elapsed >= duration
    }

    private fun snapshot(announce: Boolean): MotionFrame {
        val current = requireNotNull(kind)
        val t = if (current == MotionKind.SLING) 0f else travelT()
        val cards = tracks.map { sample(it, t) }
        val arrivedNow = isArrived()
        val just = announce && arrivedNow && !announced
        if (announce && arrivedNow) announced = true
        arrived = arrivedNow
        val progress = if (current == MotionKind.SLING) {
            val span = (tracks.first().to - tracks.first().from).length().coerceAtLeast(0.001f)
            (1f - (cards.first().position - (springTarget ?: cards.first().position)).length() / span).coerceIn(0f, 1f)
        } else t
        val tether = tetherOrigin?.let { origin ->
            val tip = cards.first().position
            TetherSample(origin, tip, ThoughtPaths.beads(origin, tip))
        }
        return MotionFrame(
            current,
            progress,
            cards,
            tether,
            if (current == MotionKind.TOSS) tracks.first().to else null,
            arrivedNow,
            just,
            holding && !returning,
            returning,
        )
    }
}
