package com.metamanning.questlite

/** Temporary attention modes never rewrite the user's saved window visibility. */
class WorkspaceSession {
    var focusedId: String? = null
        private set
    var resting: Boolean = false
        private set

    fun focus(id: String) { focusedId = id; resting = false }
    fun rest() { resting = true }
    fun forgetFocus() { focusedId = null }
    fun restore() { focusedId = null; resting = false }
    fun visible(id: String, hidden: Boolean): Boolean =
        !resting && !hidden && (focusedId == null || focusedId == id)

    companion object {
        fun kitId(title: String): String = java.util.UUID.nameUUIDFromBytes(
            "luma-kit:$title".toByteArray(Charsets.UTF_8)
        ).toString()
    }
}
