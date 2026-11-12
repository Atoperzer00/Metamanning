package com.metamanning.questlite

import kotlin.math.abs

/** Menu and controller gestures. Head and hand rays stay off until a headset test enables them. */
enum class GesturePhase { IDLE, AIMING, SELECTED, PREVIEW }

enum class GestureKind { WEB_SLING, TOSS }

enum class RaySource { MENU, CONTROLLER, HEAD, HAND }

data class GestureResult(
    val ok: Boolean,
    val phase: GesturePhase,
    val reason: String,
    val committed: Boolean = false,
    val canceled: Boolean = false,
)

/**
 * idle -> aiming -> selected -> preview -> commit or cancel.
 * Web-sling refuses ordinary scrolling and grabbing, and it can be canceled before commit.
 */
class GestureMachine {
    var headHandRayEnabled: Boolean = false
    var phase: GesturePhase = GesturePhase.IDLE
        private set
    var kind: GestureKind? = null
        private set
    var targetId: String? = null
        private set
    var tetherVisible: Boolean = false
        private set

    fun aim(kind: GestureKind, source: RaySource, targetId: String?, scrolling: Boolean, grabbed: Boolean): GestureResult {
        if (phase != GesturePhase.IDLE) return refused("Finish or cancel the current gesture.")
        if ((source == RaySource.HEAD || source == RaySource.HAND) && !headHandRayEnabled) {
            return refused("Head and hand ray stay off until a headset test.")
        }
        if (kind == GestureKind.WEB_SLING && (scrolling || grabbed)) {
            return refused("Web-sling stays off during scrolling and grabbing.")
        }
        if (grabbed) return refused("Release the grabbed object before starting a gesture.")
        this.kind = kind
        this.targetId = targetId
        tetherVisible = false
        phase = GesturePhase.AIMING
        return GestureResult(true, phase, "Aiming.")
    }

    fun setAimTarget(targetId: String): GestureResult {
        if (phase != GesturePhase.AIMING) return refused("Aim before choosing a thought.")
        this.targetId = targetId
        return GestureResult(true, phase, "Aimed at a thought.")
    }

    fun select(): GestureResult {
        if (phase != GesturePhase.AIMING || targetId == null) return refused("Select a thought while aiming.")
        phase = GesturePhase.SELECTED
        return GestureResult(true, phase, "Selected.")
    }

    fun preview(): GestureResult {
        if (phase != GesturePhase.SELECTED) return refused("Select a thought before the preview.")
        phase = GesturePhase.PREVIEW
        tetherVisible = kind == GestureKind.WEB_SLING
        return GestureResult(true, phase, if (tetherVisible) "Tether preview. Commit or cancel." else "Target preview. Commit or cancel.")
    }

    fun commit(scrolling: Boolean, grabbed: Boolean): GestureResult {
        if (phase != GesturePhase.PREVIEW) return refused("Preview a target before committing.")
        if (grabbed || (kind == GestureKind.WEB_SLING && scrolling)) {
            reset()
            return GestureResult(false, GesturePhase.IDLE, "Canceled. Scrolling and grabbing do not complete the gesture.", canceled = true)
        }
        reset()
        return GestureResult(true, GesturePhase.IDLE, "Committed.", committed = true)
    }

    fun cancel(): GestureResult {
        if (phase == GesturePhase.IDLE) return GestureResult(true, phase, "Nothing to cancel.", canceled = false)
        reset()
        return GestureResult(true, GesturePhase.IDLE, "Canceled.", canceled = true)
    }

    fun interrupt(scrolling: Boolean, grabbed: Boolean): GestureResult {
        if (phase == GesturePhase.IDLE) return GestureResult(true, phase, "Idle.")
        if (grabbed || (kind == GestureKind.WEB_SLING && scrolling)) return cancel()
        return GestureResult(true, phase, "Continuing.")
    }

    /** Controller and menu path: aim, select, and hold the preview. Commit stays separate. */
    fun menuPreview(kind: GestureKind, targetId: String, scrolling: Boolean, grabbed: Boolean): GestureResult {
        val aimed = aim(kind, RaySource.MENU, targetId, scrolling, grabbed)
        if (!aimed.ok) return aimed
        val selected = select()
        if (!selected.ok) return selected
        return preview()
    }

    private fun reset() {
        phase = GesturePhase.IDLE
        kind = null
        targetId = null
        tetherVisible = false
    }

    private fun refused(reason: String) = GestureResult(false, phase, reason)
}

/** Closest thought along a ray. Equal distances prefer the smaller id. */
fun rayHit(origin: Vec3, direction: Vec3, points: List<Pair<String, Vec3>>, radius: Float = 0.35f, maxDistance: Float = 8f): String? {
    val dir = direction.normalized()
    return points.mapNotNull { (id, point) ->
        val along = (point - origin).dot(dir)
        if (along < 0.05f || along > maxDistance) return@mapNotNull null
        val miss = (point - (origin + dir * along)).length()
        if (miss > radius) null else Triple(id, along, miss)
    }.minWithOrNull(compareBy({ it.second }, { it.third }, { it.first }))?.first
}

data class SpringBody(val position: Vec3, val velocity: Vec3)

object TetherSpring {
    fun step(body: SpringBody, target: Vec3, dtSeconds: Float): SpringBody {
        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        val stiffness = 42f
        val damping = 13f
        val acceleration = (target - body.position) * stiffness - body.velocity * damping
        val velocity = body.velocity + acceleration * dt
        val position = body.position + velocity * dt
        require(position.finite() && velocity.finite()) { "Tether became unstable" }
        return SpringBody(position, velocity)
    }
}

/**
 * Contract for the existing Pull here action. Headset comfort is still untested;
 * this only fixes the decision the menu is allowed to make.
 */
object PullPolicy {
    const val DISTANCE_M = 1.05f
    data class Decision(val moved: Boolean, val unhide: Boolean, val unlock: Boolean, val persist: Boolean, val reason: String)
    fun decide(grabbed: Boolean): Decision = if (grabbed) {
        Decision(false, false, false, false, "Release this window before pulling it closer.")
    } else {
        Decision(true, true, true, true, "Pulled into reach.")
    }
}

/**
 * Seated reading layout. Coordinates use the library frame: z is extra distance past
 * [PullPolicy.DISTANCE_M]. Recall and new windows stay inside the comfort band.
 * This does not enable a head or hand ray.
 */
object ReadingArc {
    const val MIN_DISTANCE_M = 0.75f
    const val MAX_DISTANCE_M = 2.0f
    const val GAP_M = 0.52f

    fun comfortable(distanceM: Float) = distanceM.isFinite() && distanceM in MIN_DISTANCE_M..MAX_DISTANCE_M

    fun panelDistance(): Float {
        check(comfortable(PullPolicy.DISTANCE_M))
        return PullPolicy.DISTANCE_M
    }

    fun seats(count: Int): List<Vec3> {
        require(count in 1..ThoughtGraph.MAX_VISIBLE_CARDS) { "Recall needs between one and twelve open cards" }
        val span = (count - 1) * GAP_M
        return List(count) { index ->
            Vec3(-span / 2f + index * GAP_M, 0f, 0f)
        }
    }
}

fun springSettled(body: SpringBody, target: Vec3) = (body.position - target).length() < 0.02f && body.velocity.length() < 0.05f && abs(body.position.x) < 100f
