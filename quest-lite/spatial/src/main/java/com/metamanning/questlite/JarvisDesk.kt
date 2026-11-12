package com.metamanning.questlite

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desk tools adapted from the JarvisVR import (MIT, https://github.com/sumitaich1998/jarvisvr).
 * Arc, grid, and stack follow `LayoutArranger`. The timer follows the `pomodoro` schema in
 * `holo-tools/registry.json`, including the long break the Unity widget named and did not time.
 * Checklists follow the `todo_list` item shape. Nothing here talks to a Jarvis backend, and
 * head and hand rays stay off. Positions are written in the seated library frame.
 */
object HoloArranger {
    const val CARD_SPACING = 0.66f
    private const val VERTICAL_BAND_M = 1.6f
    val ARRANGEMENTS = setOf("arc", "grid", "stack")

    /**
     * Jarvis local meters: x right, y up, z forward from the user.
     * A non-positive spacing becomes 0.25 m, matching `LayoutArranger`.
     */
    fun jarvisLocal(arrangement: String, count: Int, spacing: Float = 0.25f): List<Vec3> {
        require(arrangement in ARRANGEMENTS) { "Unknown arrangement" }
        require(count in 1..ThoughtGraph.MAX_VISIBLE_CARDS) { "Arrange between one and twelve thoughts" }
        val gap = if (spacing.isFinite() && spacing > 0f) spacing else 0.25f
        return List(count) { index -> jarvisPos(arrangement, index, count, gap) }
    }

    /** Same shape, with the group kept on the seated reading plane. */
    fun readingSpots(arrangement: String, count: Int, spacing: Float = CARD_SPACING): List<Vec3> {
        val gap = if (spacing.isFinite() && spacing > 0f) spacing else 0.25f
        val local = jarvisLocal(arrangement, count, gap)
        val center = forwardDistance(gap)
        val shifted = local.map { spot ->
            val depth = (PullPolicy.DISTANCE_M + (spot.z - center)).coerceIn(ReadingArc.MIN_DISTANCE_M, ReadingArc.MAX_DISTANCE_M)
            Vec3(spot.x, spot.y, depth - PullPolicy.DISTANCE_M)
        }
        return fitVertical(shifted)
    }

    private fun jarvisPos(arrangement: String, index: Int, count: Int, spacing: Float): Vec3 {
        val ahead = forwardDistance(spacing)
        return when (arrangement) {
            "grid" -> {
                val cols = max(1, ceil(sqrt(count.toFloat())).toInt())
                val row = index / cols
                val col = index % cols
                val width = (cols - 1) * spacing
                Vec3(col * spacing - width * 0.5f, -row * spacing, ahead)
            }
            "stack" -> Vec3(0f, index * spacing, ahead)
            else -> {
                val spreadDeg = min(150f, spacing * 40f * max(1, count - 1))
                val across = if (count > 1) index.toFloat() / (count - 1) - 0.5f else 0f
                val angle = across * spreadDeg * (PI.toFloat() / 180f)
                Vec3(sin(angle) * ahead, 0f, cos(angle) * ahead)
            }
        }
    }

    private fun forwardDistance(spacing: Float) = max(0.8f, spacing * 4f)

    private fun fitVertical(spots: List<Vec3>): List<Vec3> {
        if (spots.size < 2) return spots
        val span = spots.maxOf { it.y } - spots.minOf { it.y }
        if (span <= VERTICAL_BAND_M || span < 1e-4f) return spots
        val scale = VERTICAL_BAND_M / span
        val mid = (spots.maxOf { it.y } + spots.minOf { it.y }) / 2f
        return spots.map { it.copy(y = (it.y - mid) * scale) }
    }
}

enum class FocusPhase { IDLE, FOCUS, SHORT_BREAK, LONG_BREAK }

enum class FocusRun { IDLE, RUNNING, PAUSED }

data class FocusTimer(
    val phase: FocusPhase = FocusPhase.IDLE,
    val state: FocusRun = FocusRun.IDLE,
    val remainingMs: Long = 0,
    val focusMs: Long = FocusClock.DEFAULT_FOCUS_MS,
    val breakMs: Long = FocusClock.DEFAULT_BREAK_MS,
    val longBreakMs: Long = FocusClock.DEFAULT_LONG_BREAK_MS,
    val completedSessions: Int = 0,
    val task: String = "",
    val deadlineMs: Long? = null,
) {
    init {
        require(focusMs in 1..FocusClock.MAX_PHASE_MS && breakMs in 1..FocusClock.MAX_PHASE_MS && longBreakMs in 1..FocusClock.MAX_PHASE_MS) {
            "Focus durations are out of range"
        }
        require(remainingMs in 0..FocusClock.MAX_PHASE_MS) { "Remaining time is out of range" }
        require(completedSessions in 0..10_000) { "Session count is out of range" }
        require(task.length <= 60 && task.none { it.code < 32 }) { "Invalid focus task" }
        require(deadlineMs == null || deadlineMs >= 0L) { "Invalid focus deadline" }
        require(state != FocusRun.RUNNING || deadlineMs != null) { "A running timer needs a deadline" }
        require(phase != FocusPhase.IDLE || state == FocusRun.IDLE) { "An idle timer is not running" }
    }
}

data class FocusTick(val timer: FocusTimer, val advanced: Int)

object FocusClock {
    const val DEFAULT_FOCUS_MS = 1_500_000L
    const val DEFAULT_BREAK_MS = 300_000L
    /** Fifteen minutes. The catalog has `long_break`; the Unity widget never counted it. */
    const val DEFAULT_LONG_BREAK_MS = 900_000L
    const val MAX_PHASE_MS = 180L * 60_000L
    const val SESSIONS_BEFORE_LONG_BREAK = 4

    fun start(timer: FocusTimer, nowMs: Long, task: String = timer.task): FocusTimer {
        require(nowMs >= 0L) { "Invalid clock" }
        val label = task.trim()
        return timer.copy(
            phase = FocusPhase.FOCUS,
            state = FocusRun.RUNNING,
            remainingMs = timer.focusMs,
            deadlineMs = nowMs + timer.focusMs,
            task = label,
        )
    }

    fun pause(timer: FocusTimer, nowMs: Long): FocusTimer {
        require(timer.state == FocusRun.RUNNING && timer.deadlineMs != null) { "The timer is not running" }
        val remaining = (timer.deadlineMs - nowMs).coerceAtLeast(0L).coerceAtMost(MAX_PHASE_MS)
        return timer.copy(state = FocusRun.PAUSED, remainingMs = remaining, deadlineMs = null)
    }

    fun resume(timer: FocusTimer, nowMs: Long): FocusTimer {
        require(timer.state == FocusRun.PAUSED && timer.remainingMs > 0L && timer.phase != FocusPhase.IDLE) { "The timer is not paused" }
        require(nowMs >= 0L) { "Invalid clock" }
        return timer.copy(state = FocusRun.RUNNING, deadlineMs = nowMs + timer.remainingMs)
    }

    fun skip(timer: FocusTimer, nowMs: Long): FocusTimer {
        require(timer.phase != FocusPhase.IDLE && timer.state != FocusRun.IDLE) { "Start a focus timer first" }
        return advance(timer, nowMs)
    }

    fun reset(timer: FocusTimer): FocusTimer = FocusTimer(
        focusMs = timer.focusMs,
        breakMs = timer.breakMs,
        longBreakMs = timer.longBreakMs,
        completedSessions = timer.completedSessions,
    )

    fun catchUp(timer: FocusTimer, nowMs: Long): FocusTick {
        if (timer.state != FocusRun.RUNNING || timer.deadlineMs == null || timer.deadlineMs > nowMs) return FocusTick(timer, 0)
        var current = timer
        var advanced = 0
        while (current.state == FocusRun.RUNNING && current.deadlineMs != null && current.deadlineMs <= nowMs && advanced < 48) {
            current = advance(current, current.deadlineMs)
            advanced += 1
        }
        if (advanced >= 48) return FocusTick(reset(current), advanced)
        return FocusTick(current, advanced)
    }

    fun label(timer: FocusTimer, nowMs: Long): String {
        if (timer.phase == FocusPhase.IDLE || timer.state == FocusRun.IDLE) {
            return "Focus timer off" + sessions(timer)
        }
        val remaining = if (timer.state == FocusRun.RUNNING && timer.deadlineMs != null) {
            (timer.deadlineMs - nowMs).coerceAtLeast(0L)
        } else timer.remainingMs
        val name = when (timer.phase) {
            FocusPhase.FOCUS -> "FOCUS"
            FocusPhase.SHORT_BREAK -> "BREAK"
            FocusPhase.LONG_BREAK -> "LONG BREAK"
            FocusPhase.IDLE -> "FOCUS"
        }
        val paused = if (timer.state == FocusRun.PAUSED) " paused" else ""
        val task = if (timer.task.isBlank()) "" else " · ${timer.task}"
        return "$name ${clock(remaining)}$paused$task" + sessions(timer)
    }

    fun advancedMessage(timer: FocusTimer) = when (timer.phase) {
        FocusPhase.SHORT_BREAK -> "Short break started."
        FocusPhase.LONG_BREAK -> "Long break started."
        FocusPhase.FOCUS -> "Next focus started."
        FocusPhase.IDLE -> "Focus timer is off."
    }

    fun encode(timer: FocusTimer): String {
        val json = JSONObject()
            .put("phase", phaseName(timer.phase))
            .put("state", timer.state.name.lowercase())
            .put("remainingMs", timer.remainingMs)
            .put("focusMs", timer.focusMs)
            .put("breakMs", timer.breakMs)
            .put("longBreakMs", timer.longBreakMs)
            .put("completedSessions", timer.completedSessions)
            .put("task", timer.task)
        if (timer.deadlineMs != null) json.put("deadlineMs", timer.deadlineMs)
        return json.toString().also { decode(it) }
    }

    fun decode(json: String): FocusTimer {
        require(json.length <= 800) { "Saved timer is too large" }
        val root = JSONObject(json)
        val phase = phaseOf(root.getString("phase"))
        val state = when (root.getString("state")) {
            "running" -> FocusRun.RUNNING
            "paused" -> FocusRun.PAUSED
            "idle" -> FocusRun.IDLE
            else -> error("Unknown timer state")
        }
        return FocusTimer(
            phase = phase,
            state = state,
            remainingMs = root.getLong("remainingMs"),
            focusMs = root.optLong("focusMs", DEFAULT_FOCUS_MS),
            breakMs = root.optLong("breakMs", DEFAULT_BREAK_MS),
            longBreakMs = root.optLong("longBreakMs", DEFAULT_LONG_BREAK_MS),
            completedSessions = root.optInt("completedSessions", 0),
            task = root.optString("task"),
            deadlineMs = if (root.has("deadlineMs")) root.getLong("deadlineMs") else null,
        )
    }

    private fun advance(timer: FocusTimer, nowMs: Long): FocusTimer {
        require(nowMs >= 0L) { "Invalid clock" }
        return when (timer.phase) {
            FocusPhase.FOCUS -> {
                val done = timer.completedSessions + 1
                val longBreak = done % SESSIONS_BEFORE_LONG_BREAK == 0
                val remaining = if (longBreak) timer.longBreakMs else timer.breakMs
                timer.copy(
                    phase = if (longBreak) FocusPhase.LONG_BREAK else FocusPhase.SHORT_BREAK,
                    state = FocusRun.RUNNING,
                    remainingMs = remaining,
                    completedSessions = done,
                    deadlineMs = nowMs + remaining,
                )
            }
            FocusPhase.SHORT_BREAK, FocusPhase.LONG_BREAK -> timer.copy(
                phase = FocusPhase.FOCUS,
                state = FocusRun.RUNNING,
                remainingMs = timer.focusMs,
                deadlineMs = nowMs + timer.focusMs,
            )
            FocusPhase.IDLE -> timer
        }
    }

    private fun sessions(timer: FocusTimer) = if (timer.completedSessions == 0) "" else " · ${timer.completedSessions} done"

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).toInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private fun phaseName(phase: FocusPhase) = when (phase) {
        FocusPhase.IDLE -> "idle"
        FocusPhase.FOCUS -> "focus"
        FocusPhase.SHORT_BREAK -> "short_break"
        FocusPhase.LONG_BREAK -> "long_break"
    }

    private fun phaseOf(name: String) = when (name) {
        "idle" -> FocusPhase.IDLE
        "focus" -> FocusPhase.FOCUS
        "short_break" -> FocusPhase.SHORT_BREAK
        "long_break" -> FocusPhase.LONG_BREAK
        else -> error("Unknown focus phase")
    }
}

data class TaskItem(val id: String, val text: String, val done: Boolean)

/** `todo_list` items stored in a task's content so search and undo keep working. */
object TaskSteps {
    const val PREFIX = "todo:"
    const val MAX_ITEMS = 8

    fun peek(content: String) = if (!content.startsWith(PREFIX)) null else runCatching { read(content) }.getOrNull()

    fun read(content: String): List<TaskItem>? {
        if (!content.startsWith(PREFIX)) return null
        val array = JSONArray(content.removePrefix(PREFIX))
        require(array.length() in 1..MAX_ITEMS) { "A checklist needs between one and eight steps" }
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val keys = mutableSetOf<String>()
            val names = item.keys()
            while (names.hasNext()) keys += names.next()
            require(keys == setOf("id", "text", "done")) { "Unexpected checklist field" }
            TaskItem(item.getString("id"), item.getString("text"), item.getBoolean("done")).also { check(it) }
        }.also { items -> require(items.map { it.id }.distinct().size == items.size) { "Duplicate step" } }
    }

    fun write(items: List<TaskItem>): String {
        require(items.isNotEmpty() && items.size <= MAX_ITEMS) { "A checklist needs between one and eight steps" }
        require(items.map { it.id }.distinct().size == items.size) { "Duplicate step" }
        val array = JSONArray()
        items.forEach { item ->
            check(item)
            array.put(JSONObject().put("id", item.id).put("text", item.text).put("done", item.done))
        }
        val encoded = PREFIX + array.toString()
        require(encoded.length <= 500) { "The checklist does not fit on the thought" }
        return encoded
    }

    fun toggle(content: String, itemId: String): String {
        val items = read(content) ?: error("This task has no steps")
        require(items.any { it.id == itemId }) { "Unknown step" }
        return write(items.map { if (it.id == itemId) it.copy(done = !it.done) else it })
    }

    fun add(content: String, id: String, text: String): String {
        val items = read(content) ?: error("This task has no steps")
        return write(items + TaskItem(id, text.trim(), false))
    }

    fun progress(items: List<TaskItem>): String {
        val open = items.count { !it.done }
        return if (open == 0) "all ${items.size} done" else "$open of ${items.size} open"
    }

    private fun check(item: TaskItem) {
        require(item.id.matches(Regex("[a-z0-9]{1,12}"))) { "Invalid step" }
        require(item.text.isNotBlank() && item.text.length <= 80 && item.text.none { it.code < 32 }) { "Invalid step" }
    }
}
