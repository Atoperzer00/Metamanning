package com.metamanning.questlite

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RoomLayoutTest {
    private val room = UUID.randomUUID().toString()
    private fun layout() = RoomLayout(room, "Office", listOf(WindowLayout(slot = 0, position = listOf(1f, 1.5f, -2f))))
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
    @Test fun roundTripPreservesRoomRelativePoseAndWindowOptions() {
        val saved = layout().copy(windows = listOf(layout().windows[0].copy(locked = true, hidden = true, size = 1.4f)))
        assertEquals(saved, RoomLayoutCodec.decode(RoomLayoutCodec.encode(saved), room))
    }
    @Test fun differentRoomCannotRestoreOfficeWindows() {
        rejected { RoomLayoutCodec.decode(RoomLayoutCodec.encode(layout()), UUID.randomUUID().toString()) }
    }
    @Test fun officialConversationUrlsOnly() {
        assertTrue(RoomLayoutCodec.safeUrl("https://chatgpt.com/c/abc?model=auto"))
        listOf("javascript:alert(1)", "https://chatgpt.com.evil.test/c/a", "https://evil@chatgpt.com/c/a", "http://chatgpt.com/", "https://chatgpt.com:8080/").forEach {
            assertFalse(it, RoomLayoutCodec.safeUrl(it))
        }
    }
    @Test fun unsupportedVersionCannotSilentlyOverwriteRooms() {
        rejected { RoomLayoutCodec.decode(RoomLayoutCodec.encode(layout()).replace("\"version\":1", "\"version\":9"), room) }
    }
    @Test fun duplicateWindowSlotsAreRejected() {
        val first = layout().windows.first()
        rejected { RoomLayoutCodec.encode(layout().copy(windows = listOf(first, first.copy(id = UUID.randomUUID().toString())))) }
    }
    @Test fun invalidQuaternionAndExtremePositionsAreRejected() {
        val first = layout().windows.first()
        rejected { RoomLayoutCodec.encode(layout().copy(windows = listOf(first.copy(rotation = listOf(0f, 0f, 0f, 0f))))) }
        rejected { RoomLayoutCodec.encode(layout().copy(windows = listOf(first.copy(position = listOf(10000f, 0f, 0f))))) }
    }
    @Test fun invalidSizesAndExcessWindowsAreRejected() {
        val first = layout().windows.first()
        rejected { RoomLayoutCodec.encode(layout().copy(windows = listOf(first.copy(size = Float.NaN)))) }
        rejected { RoomLayoutCodec.encode(layout().copy(windows = List(5) { WindowLayout(slot = it) })) }
    }
    @Test fun corruptAndOversizedJsonAreRejected() {
        rejected { RoomLayoutCodec.decode("{", room) }
        rejected { RoomLayoutCodec.decode(" ".repeat(RoomLayoutCodec.MAX_BYTES + 1), room) }
    }
}
