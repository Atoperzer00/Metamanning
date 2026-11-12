package com.metamanning.questlite

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONObject

/**
 * One host session. A guest joins and receives the current approved envelope.
 * The host never applies a payload the guest sends. Loopback is what the unit test covers.
 */
class CoThinkHost(
    val sessionId: String,
    private val session: () -> CoThinkSession?,
    private val graph: () -> ThoughtGraph,
) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("0.0.0.0"))
    val port: Int = server.localPort
    @Volatile private var running = true
    private val thread = Thread({
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            try {
                serve(socket)
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }
    }, "co-think").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3_000
        val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
        require(line.length <= 4_000) { "Co-think join is too large" }
        val request = JSONObject(line)
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        fun fail(reason: String) {
            writer.write(JSONObject().put("version", 1).put("type", "error").put("reason", reason).toString())
            writer.write("\n")
            writer.flush()
        }
        if (request.optString("type") != "join" || request.optString("role") != "GUEST") {
            fail("Only a guest may join. The host does not accept pushed thoughts.")
            return
        }
        if (request.optString("sessionId") != sessionId) {
            fail("Session does not match this host.")
            return
        }
        val current = session()
        if (current == null || current.role != SessionRole.HOST || current.id != sessionId) {
            fail("Host session is not open.")
            return
        }
        val envelope = try {
            current.envelope(graph())
        } catch (error: Exception) {
            fail(error.message ?: "Nothing is approved.")
            return
        }
        writer.write(encodeEnvelope(envelope))
        writer.write("\n")
        writer.flush()
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        thread.join(1_000)
    }
}

fun allowedShareHost(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1") return true
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.length !in 1..3 }) return false
    val numbers = parts.map { it.toIntOrNull() ?: return false }
    if (numbers.any { it !in 0..255 }) return false
    if (numbers[0] == 10) return true
    if (numbers[0] == 192 && numbers[1] == 168) return true
    if (numbers[0] == 172 && numbers[1] in 16..31) return true
    if (numbers[0] == 169 && numbers[1] == 254) return true
    return false
}

fun requestShare(host: String, port: Int, sessionId: String, type: String = "join", role: String = "GUEST"): String {
    require(allowedShareHost(host)) { "Co-think only connects to this headset or another headset on the local network." }
    require(port in 1..65535) { "Invalid co-think port" }
    require(canonicalUuid(sessionId)) { "Invalid co-think session" }
    require(type.length <= 20 && role.length <= 20) { "Invalid co-think request" }
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 3_000)
        socket.soTimeout = 3_000
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        writer.write(JSONObject().put("version", 1).put("type", type).put("role", role).put("sessionId", sessionId).toString())
        writer.write("\n")
        writer.flush()
        val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: error("Host closed the share.")
        require(line.toByteArray(Charsets.UTF_8).size <= 256_000) { "Share envelope is too large" }
        return line
    }
}

fun pullEnvelope(host: String, port: Int, sessionId: String): SyncEnvelope {
    val line = requestShare(host, port, sessionId)
    val root = JSONObject(line)
    if (root.optString("type") == "error") error(root.optString("reason").ifBlank { "Share was refused." })
    val envelope = decodeEnvelope(line)
    require(envelope.sessionId == sessionId && envelope.role == SessionRole.HOST) { "Share did not come from the host." }
    return envelope
}
