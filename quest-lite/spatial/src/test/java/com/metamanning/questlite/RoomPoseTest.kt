package com.metamanning.questlite

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual SDK's pose math; no OpenXR scene or simulated relocalization claim. */
class RoomPoseTest {
    private fun close(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
        assertEquals(expected.z, actual.z, 0.0001f)
    }
    @Test fun roomRelativePlacementSurvivesChangedTrackingOrigin() {
        val local = Pose(Vector3(0.7f, 1.2f, 1.9f), Quaternion(0f, 30f, 0f))
        val firstRoom = Pose(Vector3(5f, 0f, -3f), Quaternion(0f, 60f, 0f))
        val saved = firstRoom.inverse() * (firstRoom * local)
        close(local.t, saved.t)
        val relocatedRoom = Pose(Vector3(-2f, 0f, 7f), Quaternion(0f, -35f, 0f))
        val restoredWorld = relocatedRoom * saved
        close(local.t, (relocatedRoom.inverse() * restoredWorld).t)
        assertFalse((firstRoom * local).isWithinDistance(restoredWorld, 0.1f))
    }
    @Test fun quaternionSerializationUsesSdkWxyzOrdering() {
        val rotation = Quaternion(10f, 75f, -5f)
        val saved = listOf(rotation.w, rotation.x, rotation.y, rotation.z)
        val restored = Quaternion(saved[0], saved[1], saved[2], saved[3])
        close(rotation * Vector3(0f, 0f, 1f), restored * Vector3(0f, 0f, 1f))
    }
    @Test fun placementUsesUserFacingForwardRatherThanGlobalZ() {
        val viewer = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 90f, 0f))
        val target = viewer * Pose(Vector3(0f, -0.12f, 1.3f))
        close(Vector3(0f, -0.12f, 1.3f), viewer.inverse() * target.t)
        assertEquals(1.48f, target.t.y, 0.0001f)
    }
}
