package com.metamanning.questlite

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSessionTest {
    @Test fun focusAndRestRestoreWithoutUnhidingPrivateWindows() {
        val session = WorkspaceSession()
        session.focus("a")
        assertTrue(session.visible("a", false))
        assertFalse(session.visible("b", false))
        session.rest()
        assertFalse(session.visible("a", false))
        session.restore()
        assertTrue(session.visible("b", false))
        assertFalse(session.visible("private", true))
    }
    @Test fun focusExitsRestAndKeepsOtherWindowsPaused() {
        val session = WorkspaceSession()
        session.rest(); session.focus("b")
        assertTrue(session.visible("b", false))
        assertFalse(session.visible("a", false))
    }
    @Test fun roomChangesAndClosedFocusCannotEndRest() {
        val session = WorkspaceSession()
        session.focus("old-room-window"); session.rest(); session.forgetFocus()
        assertTrue(session.resting)
        assertFalse(session.visible("new-room-window", false))
        session.restore()
        assertTrue(session.visible("new-room-window", false))
    }
    @Test fun personalToolsKeepTheirStateKeyAcrossRoomsAndRestarts() {
        assertEquals(WorkspaceSession.kitId("Today"), WorkspaceSession.kitId("Today"))
        assertNotEquals(WorkspaceSession.kitId("Today"), WorkspaceSession.kitId("Mind Inbox"))
        java.util.UUID.fromString(WorkspaceSession.kitId("Today"))
    }
}
