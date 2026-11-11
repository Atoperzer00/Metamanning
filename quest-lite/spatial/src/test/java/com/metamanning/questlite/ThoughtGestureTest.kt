package com.metamanning.questlite

import org.junit.Assert.*
import org.junit.Test

class ThoughtGestureTest {
    @Test fun slingMovesThroughPreviewAndRefusesScrollOrGrab() {
        val gestures = GestureMachine()
        val blocked = gestures.aim(GestureKind.WEB_SLING, RaySource.MENU, "thought", scrolling = true, grabbed = false)
        assertFalse(blocked.ok)
        assertEquals(GesturePhase.IDLE, gestures.phase)
        assertFalse(gestures.aim(GestureKind.WEB_SLING, RaySource.CONTROLLER, "thought", scrolling = false, grabbed = true).ok)
        val preview = gestures.menuPreview(GestureKind.WEB_SLING, "thought", scrolling = false, grabbed = false)
        assertTrue(preview.ok)
        assertEquals(GesturePhase.PREVIEW, gestures.phase)
        assertTrue(gestures.tetherVisible)
        val canceled = gestures.commit(scrolling = true, grabbed = false)
        assertFalse(canceled.committed)
        assertTrue(canceled.canceled)
        assertEquals(GesturePhase.IDLE, gestures.phase)
        assertFalse(gestures.tetherVisible)
    }

    @Test fun headAndHandRayStayOffUntilEnabled() {
        val gestures = GestureMachine()
        assertFalse(gestures.headHandRayEnabled)
        assertFalse(gestures.aim(GestureKind.WEB_SLING, RaySource.HAND, "thought", false, false).ok)
        assertFalse(gestures.aim(GestureKind.TOSS, RaySource.HEAD, "thought", false, false).ok)
        assertEquals(GesturePhase.IDLE, gestures.phase)
        gestures.headHandRayEnabled = true
        assertTrue(gestures.aim(GestureKind.WEB_SLING, RaySource.HAND, "thought", false, false).ok)
    }

    @Test fun controllerRayChoosesTheClosestThought() {
        val hit = rayHit(Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, 1f), listOf(
            "far" to Vec3(0f, 1.5f, 4f),
            "near" to Vec3(0.1f, 1.5f, 2f),
            "aside" to Vec3(2f, 1.5f, 2f),
        ))
        assertEquals("near", hit)
        assertNull(rayHit(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), listOf("behind" to Vec3(0f, 0f, -1f))))
    }

    @Test fun tetherSpringsTowardTheTargetAndCanBeAbandoned() {
        val target = Vec3(1f, 0f, 0f)
        var body = SpringBody(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
        repeat(180) { body = TetherSpring.step(body, target, 1f / 72f) }
        assertTrue(springSettled(body, target))
        val home = Vec3(0f, 0f, 0f)
        repeat(180) { body = TetherSpring.step(body, home, 1f / 72f) }
        assertTrue(springSettled(body, home))
    }

    @Test fun pullHereRefusesAGrabAndOtherwiseUnlocksAtReadingDistance() {
        val grabbed = PullPolicy.decide(grabbed = true)
        assertFalse(grabbed.moved)
        assertFalse(grabbed.persist)
        val pulled = PullPolicy.decide(grabbed = false)
        assertTrue(pulled.moved && pulled.unhide && pulled.unlock && pulled.persist)
        assertEquals(1.05f, PullPolicy.DISTANCE_M, 0.0001f)
    }

    @Test fun tossPreviewCanBeCanceledBeforeCommit() {
        val gestures = GestureMachine()
        val preview = gestures.menuPreview(GestureKind.TOSS, "thought", scrolling = true, grabbed = false)
        assertEquals(GesturePhase.PREVIEW, gestures.phase)
        assertFalse(gestures.tetherVisible)
        val canceled = gestures.cancel()
        assertTrue(canceled.canceled)
        assertFalse(canceled.committed)
        assertEquals(GesturePhase.IDLE, gestures.phase)
        assertTrue(preview.ok)
    }
}
