package com.metamanning.questlite

import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Room-relative data only. A saved tracking-origin pose is deliberately not a room anchor. */
data class WindowLayout(
    val id: String = UUID.randomUUID().toString(),
    val slot: Int,
    val title: String = "Conversation",
    val url: String = "https://chatgpt.com/",
    val position: List<Float> = listOf(0f, 1.4f, 1.2f),
    // Serialized as w,x,y,z, matching the Meta Spatial SDK constructor.
    val rotation: List<Float> = listOf(1f, 0f, 0f, 0f),
    val size: Float = 1f,
    val locked: Boolean = false,
    val hidden: Boolean = false,
)

data class RoomLayout(val roomId: String, val name: String, val windows: List<WindowLayout>)

object RoomLayoutCodec {
    const val MAX_WINDOWS = 4
    const val MAX_BYTES = 65536
    fun safeUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "chatgpt.com" && uri.userInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    private fun uuid(value: String): String {
        require(UUID.fromString(value).toString() == value.lowercase()) { "Invalid room or window identifier" }
        return value
    }
    private fun title(value: String): String {
        require(value.isNotBlank() && value.length <= 60 && value.none { it.code < 32 }) { "Invalid name" }
        return value
    }
    private fun vector(array: JSONArray, size: Int): List<Float> {
        require(array.length() == size) { "Invalid saved transform" }
        return List(size) { array.getDouble(it).toFloat().also { n -> require(n.isFinite()) { "Non-finite transform" } } }
    }
    fun decode(json: String, recognizedRoom: String): RoomLayout {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Layout file is too large" }
        val root = JSONObject(json)
        require(root.getInt("version") == 1) { "Unsupported layout version" }
        val roomId = uuid(root.getString("roomId"))
        require(roomId == uuid(recognizedRoom)) { "This layout belongs to another room" }
        val entries = root.getJSONArray("windows")
        require(entries.length() <= MAX_WINDOWS) { "Too many windows" }
        val windows = List(entries.length()) { i ->
            val entry = entries.getJSONObject(i)
            val slot = entry.getInt("slot")
            require(slot in 0 until MAX_WINDOWS) { "Invalid window slot" }
            val position = vector(entry.getJSONArray("position"), 3)
            require(position.all { kotlin.math.abs(it) <= 30f }) { "Window is outside supported room bounds" }
            val rotation = vector(entry.getJSONArray("rotation"), 4)
            val norm = rotation.sumOf { (it * it).toDouble() }
            require(norm in 0.9..1.1) { "Invalid saved rotation" }
            val size = entry.getDouble("size").toFloat()
            require(size.isFinite() && size in 0.55f..1.8f) { "Invalid window size" }
            val url = entry.getString("url")
            require(safeUrl(url)) { "Only official ChatGPT URLs can be restored" }
            WindowLayout(uuid(entry.getString("id")), slot, title(entry.getString("title")), url,
                position, rotation, size, entry.getBoolean("locked"), entry.getBoolean("hidden"))
        }
        require(windows.map { it.id }.distinct().size == windows.size && windows.map { it.slot }.distinct().size == windows.size) { "Duplicate saved window" }
        return RoomLayout(roomId, title(root.getString("name")), windows)
    }
    fun encode(layout: RoomLayout): String {
        val entries = JSONArray()
        layout.windows.forEach { window -> entries.put(JSONObject().apply {
            put("id", window.id); put("slot", window.slot); put("title", window.title); put("url", window.url)
            put("position", JSONArray(window.position)); put("rotation", JSONArray(window.rotation)); put("size", window.size)
            put("locked", window.locked); put("hidden", window.hidden)
        }) }
        val result = JSONObject().put("version", 1).put("roomId", layout.roomId).put("name", layout.name).put("windows", entries).toString()
        decode(result, layout.roomId) // validate saves as rigorously as imports
        return result
    }
}
