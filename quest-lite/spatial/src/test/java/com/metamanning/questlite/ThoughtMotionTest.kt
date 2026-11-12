package com.metamanning.questlite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtMotionTest {
    @Test fun slingDrawsATetherThatFollowsTheCardAndSpringsBack() {
        val motion = ThoughtMotion()
        val home = Vec3(0.7f, 0.1f, 0.55f)
        val reading = Vec3(0f, 0f, 0f)
        val origin = Vec3(0.18f, -0.35f, -0.55f)
        motion.beginSling("thought", home, reading, origin)
        val start = motion.frame()
        assertNotNull(start)
        assertNear(home, start!!.cards.single().position)
        assertNear(home, start.tether!!.tip)
        assertEquals(ThoughtPaths.BEADS, start.tether.beads.size)
        assertFalse(start.arrived)
        repeat(20) { motion.step(1f / 60f) }
        val mid = motion.frame()!!
        assertTrue((mid.cards.single().position - home).length() > 0.05f)
        assertNear(mid.cards.single().position, mid.tether!!.tip, 0.0001f)
        assertSagging(origin, mid.tether.tip, mid.tether.beads)
        val arrived = play(motion)
        assertTrue(arrived.holding)
        assertNear(reading, arrived.cards.single().position)
        motion.reverse()
        val returned = play(motion)
        assertTrue(returned.returning)
        assertNear(home, returned.cards.single().position)
        assertNotNull(returned.tether)
    }

    @Test fun tossArcsOntoTheClusterAndCanFlyBack() {
        val from = Vec3(-0.4f, 0f, 0.3f)
        val landing = Vec3(1.1f, 0.05f, 0.8f)
        val motion = ThoughtMotion()
        motion.beginToss("thought", from, landing)
        val start = motion.frame()!!
        assertNear(from, start.cards.single().position)
        assertNear(landing, start.landing!!)
        assertEquals(null, start.tether)
        repeat(18) { motion.step(1f / 60f) }
        val mid = motion.frame()!!
        val t = 18f / 60f / 0.95f
        val chord = ThoughtPaths.mix(from, landing, t)
        assertTrue(mid.cards.single().position.y > chord.y + 0.08f)
        assertTrue((mid.cards.single().position - from).length() > 0.05f)
        assertFalse(mid.arrived)
        assertNear(landing, play(motion).cards.single().position)
        motion.reverse()
        assertNear(from, play(motion).cards.single().position, 0.04f)
    }

    @Test fun constellationExpandsAwayFromTheRootAndCollapseReturns() {
        val rootAt = Vec3(0f, 0.12f, 0.7f)
        val nodes = listOf(
            "root" to rootAt,
            "left" to Vec3(-0.55f, 0.12f, 1.05f),
            "right" to Vec3(0.55f, 0.12f, 1.05f),
        )
        val openAt = mapOf("left" to Vec3(-0.72f, -0.05f, 0.2f), "right" to Vec3(0.72f, -0.05f, 0.2f))
        val expand = ThoughtMotion()
        expand.beginTravel(MotionKind.EXPAND, ThoughtPaths.expand("root", rootAt, nodes, openAt), "root")
        val before = expand.frame()!!
        assertNear(openAt.getValue("left"), before.cards.first { it.id == "left" }.position)
        val after = play(expand)
        assertNear(Vec3(-0.55f, 0.12f, 1.05f), after.cards.first { it.id == "left" }.position)
        assertTrue((after.cards.first { it.id == "left" }.position - before.cards.first { it.id == "left" }.position).length() > 0.3f)
        val spread = after.cards.map { it.id to it.position }
        val collapse = ThoughtMotion()
        collapse.beginTravel(MotionKind.COLLAPSE, ThoughtPaths.collapse("root", rootAt, spread), "root")
        val closed = play(collapse)
        closed.cards.forEach { assertNear(rootAt, it.position) }
        assertEquals(0.12f, closed.cards.first { it.id == "left" }.scale, 0.001f)
        assertEquals(1f, closed.cards.first { it.id == "root" }.scale, 0.001f)
        assertTrue(closed.cards.first { it.id == "right" }.dim > 0.8f)
    }

    @Test fun focusGathersTheIdeaDimsTheRestAndRestoreReturnsThem() {
        val homes = listOf(
            "root" to Vec3(0f, 0.12f, 0.7f),
            "inside" to Vec3(-0.72f, -0.05f, 0.2f),
            "outside" to Vec3(0.72f, -0.05f, 0.2f),
        )
        val tracks = ThoughtPaths.focus(homes, "root", setOf("root", "inside"))
        val motion = ThoughtMotion()
        motion.beginTravel(MotionKind.FOCUS, tracks, "root")
        val focused = play(motion)
        val outside = focused.cards.first { it.id == "outside" }
        val inside = focused.cards.first { it.id == "inside" }
        assertEquals(1f, outside.dim, 0.001f)
        assertEquals(0.42f, outside.scale, 0.001f)
        assertTrue(outside.position.z > homes.first { it.first == "outside" }.second.z + 0.4f)
        assertTrue((inside.position - homes.first { it.first == "inside" }.second).length() > 0.2f)
        assertEquals(0f, inside.dim, 0.001f)
        val restore = ThoughtMotion()
        restore.beginTravel(MotionKind.RESTORE, ThoughtPaths.restore(tracks), "root")
        val back = play(restore)
        assertNear(homes.first { it.first == "outside" }.second, back.cards.first { it.id == "outside" }.position)
        assertEquals(1f, back.cards.first { it.id == "outside" }.scale, 0.001f)
        assertEquals(0f, back.cards.first { it.id == "outside" }.dim, 0.001f)
        assertNear(homes.first { it.first == "inside" }.second, back.cards.first { it.id == "inside" }.position)
    }

    @Test fun readingFrameRoundTripsTheSeatedOffset() {
        val library = Vec3(0.2f, 0.15f, 0.4f)
        val local = ReadingFrame.local(library)
        assertNear(library, ReadingFrame.library(local.x, local.y, local.z))
        assertEquals(PullPolicy.DISTANCE_M + 0.4f, local.z, 0.0001f)
    }

    @Test fun sceneMotionStaysOnControllerAndMenu() {
        val room = File("src/main/java/com/metamanning/questlite/RoomActivity.kt").readText()
        assertTrue(room.contains("motion.step"))
        assertTrue(room.contains("applyLibrary"))
        assertTrue(room.contains("mesh://box"))
        assertTrue(room.contains("RaySource.CONTROLLER"))
        assertTrue(room.contains("RaySource.MENU"))
        assertTrue(room.contains("ControllerType.HAND"))
        assertTrue(room.contains("ButtonBits.ButtonA"))
        assertTrue(room.contains("ButtonBits.ButtonX"))
        assertTrue(room.contains("ButtonBits.ButtonY"))
        assertTrue(room.contains("ButtonBits.ButtonThumbRClick"))
        assertFalse(room.contains("headHandRayEnabled = true"))
        assertFalse(room.contains("RaySource.HEAD"))
        assertFalse(room.contains("RaySource.HAND"))
    }

    private fun play(motion: ThoughtMotion): MotionFrame {
        var frame = motion.frame() ?: error("idle")
        var steps = 0
        while (!frame.arrived && steps < 300) {
            frame = motion.step(1f / 60f) ?: error("stopped")
            steps += 1
        }
        assertTrue(frame.arrived)
        return frame
    }

    private fun assertNear(expected: Vec3, actual: Vec3, delta: Float = 0.02f) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
        assertEquals(expected.z, actual.z, delta)
    }

    private fun assertSagging(origin: Vec3, tip: Vec3, beads: List<Vec3>) {
        val span = tip - origin
        val spanLength = span.length().coerceAtLeast(1e-4f)
        var previous = 0f
        beads.forEach { bead ->
            val along = (bead - origin).dot(span) / (spanLength * spanLength)
            assertTrue(along > previous && along < 1f)
            previous = along
            val closest = origin + span * along
            assertTrue((bead - closest).length() <= ThoughtPaths.SAG_M + 0.001f)
            assertTrue(bead.y <= closest.y + 0.001f)
        }
    }
}
