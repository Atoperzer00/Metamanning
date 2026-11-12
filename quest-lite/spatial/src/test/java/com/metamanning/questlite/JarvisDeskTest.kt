package com.metamanning.questlite

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class JarvisDeskTest {
    @Test fun arcGridAndStackMatchTheJarvisArranger() {
        val ahead = 1f
        val spread = 0.25f * 40f * 2f
        val arc = HoloArranger.jarvisLocal("arc", 3, 0.25f)
        assertEquals(3, arc.size)
        assertEquals(0f, arc[1].x, 0.0001f)
        assertEquals(ahead, arc[1].z, 0.0001f)
        val angle = -0.5f * spread * (PI.toFloat() / 180f)
        assertEquals(sin(angle) * ahead, arc[0].x, 0.0001f)
        assertEquals(cos(angle) * ahead, arc[0].z, 0.0001f)
        assertEquals(0f, arc[0].y, 0.0001f)

        val grid = HoloArranger.jarvisLocal("grid", 3, 0.25f)
        assertEquals(Vec3(-0.125f, 0f, ahead), grid[0])
        assertEquals(Vec3(0.125f, 0f, ahead), grid[1])
        assertEquals(Vec3(-0.125f, -0.25f, ahead), grid[2])

        val stack = HoloArranger.jarvisLocal("stack", 3, 0f)
        assertEquals(Vec3(0f, 0f, ahead), stack[0])
        assertEquals(Vec3(0f, 0.25f, ahead), stack[1])
        assertEquals(Vec3(0f, 0.5f, ahead), stack[2])
        assertTrue(runCatching { HoloArranger.jarvisLocal("free", 2, 0.25f) }.isFailure)
    }

    @Test fun readingSpotsStayInTheSeatedBand() {
        listOf("arc", "grid", "stack").forEach { arrangement ->
            val spots = HoloArranger.readingSpots(arrangement, 12)
            assertEquals(12, spots.size)
            assertTrue(spots.all { spot ->
                spot.finite() && ReadingArc.comfortable(PullPolicy.DISTANCE_M + spot.z)
            })
            val height = spots.maxOf { it.y } - spots.minOf { it.y }
            assertTrue(height <= 1.61f)
        }
        val pair = HoloArranger.readingSpots("grid", 2, 0.66f)
        assertEquals(0f, pair[0].z, 0.0001f)
        assertEquals(-pair[1].x, pair[0].x, 0.0001f)
        assertTrue(pair[0].x < 0f)
    }

    @Test fun focusTimerFollowsThePomodoroPhases() {
        val durations = FocusTimer(focusMs = 1_000, breakMs = 400, longBreakMs = 2_000)
        val started = FocusClock.start(durations, 10_000L, "Open question")
        assertEquals(FocusPhase.FOCUS, started.phase)
        assertEquals("FOCUS 0:01 · Open question", FocusClock.label(started, 10_000L))
        assertSame(started, FocusClock.catchUp(started, 10_500L).timer)

        val early = FocusClock.catchUp(started, 11_000L)
        assertEquals(1, early.advanced)
        assertEquals(FocusPhase.SHORT_BREAK, early.timer.phase)
        assertEquals(400L, early.timer.remainingMs)
        assertEquals(1, early.timer.completedSessions)

        var timer = started
        repeat(7) { timer = FocusClock.skip(timer, 20_000L + it) }
        assertEquals(FocusPhase.LONG_BREAK, timer.phase)
        assertEquals(4, timer.completedSessions)

        val paused = FocusClock.pause(FocusClock.start(durations, 0L, ""), 250L)
        assertEquals(FocusRun.PAUSED, paused.state)
        assertNull(paused.deadlineMs)
        assertEquals(750L, paused.remainingMs)
        val resumed = FocusClock.resume(paused, 5_000L)
        assertEquals(5_750L, resumed.deadlineMs)
        val reset = FocusClock.reset(timer)
        assertEquals(FocusPhase.IDLE, reset.phase)
        assertEquals(4, reset.completedSessions)
        assertEquals(timer.completedSessions, FocusClock.decode(FocusClock.encode(timer)).completedSessions)
        assertEquals(timer.phase, FocusClock.decode(FocusClock.encode(timer)).phase)
        assertTrue(runCatching { FocusClock.skip(reset, 0L) }.isFailure)
        assertTrue(runCatching { FocusClock.decode("{\"phase\":\"focus\",\"state\":\"nope\",\"remainingMs\":1}") }.isFailure)
    }

    @Test fun checklistToggleRoundTripsInsideAThought() {
        var time = 1_000L
        var sequence = 0
        val library = ThoughtGraph({ time }, {
            sequence += 1
            java.util.UUID.nameUUIDFromBytes("desk-$sequence".toByteArray()).toString()
        })
        val provenance = Provenance("user", "local", time)
        val written = TaskSteps.write(listOf(
            TaskItem("remember", "What should this project remember next?", false),
            TaskItem("source", "Keep a source beside the claim", true),
        ))
        val task = library.create(ThoughtType.TASK, "Open question", written, ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), provenance)
        val toggled = TaskSteps.toggle(task.contentRef, "remember")
        library.update(task.id, provenance.copy(atMillis = 2_000)) { it.copy(contentRef = toggled) }
        assertEquals(listOf(true, true), TaskSteps.read(library.requireThought(task.id).contentRef)!!.map { it.done })
        assertEquals("all 2 done", TaskSteps.progress(TaskSteps.read(toggled)!!))
        assertEquals(task.id, library.search("beside the claim").single().id)
        library.undo()
        assertFalse(TaskSteps.read(library.requireThought(task.id).contentRef)!!.first().done)
        assertNull(TaskSteps.read("A plain note"))
        assertTrue(runCatching { TaskSteps.add(written, "remember", "Again") }.isFailure)
        assertTrue(runCatching { TaskSteps.write(List(9) { TaskItem("s$it", "Step", false) }) }.isFailure)
    }
}
