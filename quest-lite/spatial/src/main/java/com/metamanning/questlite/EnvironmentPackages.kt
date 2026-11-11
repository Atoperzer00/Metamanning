package com.metamanning.questlite

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ScenePrimitive(val shape: String, val size: Float, val color: String) {
    init {
        require(shape in SHAPES) { "Unsupported environment shape" }
        require(size > 0f && size <= 40f) { "Environment shape is outside supported bounds" }
        require(HEX.matches(color)) { "Invalid environment color" }
    }
    companion object {
        val SHAPES = setOf("sphere", "box", "cylinder", "quad")
    }
}

data class LodLevel(val level: Int, val maxDistance: Float, val costMs: Float, val primitives: List<ScenePrimitive>) {
    init {
        require(level in 0..3) { "Unsupported level of detail" }
        require(maxDistance > 0f && maxDistance <= 80f) { "Invalid level-of-detail distance" }
        require(costMs > 0f && costMs <= EnvironmentCatalog.FRAME_BUDGET_MS) { "Level of detail exceeds the frame budget" }
        require(primitives.isNotEmpty() && primitives.size <= 8) { "Level of detail needs original geometry" }
    }
}

data class SceneLight(val kind: String, val color: String, val intensity: Float, val position: Vec3) {
    init {
        require(kind in KINDS) { "Unsupported light" }
        require(HEX.matches(color)) { "Invalid light color" }
        require(intensity > 0f && intensity <= 2f) { "Invalid light intensity" }
        require(position.finite() && listOf(position.x, position.y, position.z).all { kotlin.math.abs(it) <= 30f }) { "Light is outside the scene" }
    }
    companion object {
        val KINDS = setOf("ambient", "point")
    }
}

data class OcclusionVolume(val shape: String, val center: Vec3, val size: Float) {
    init {
        require(shape in ScenePrimitive.SHAPES) { "Unsupported occlusion volume" }
        require(size > 0f && size <= 40f) { "Occlusion volume is outside supported bounds" }
        require(center.finite() && listOf(center.x, center.y, center.z).all { kotlin.math.abs(it) <= 30f }) { "Occlusion volume is outside the scene" }
    }
}

/**
 * A curated scene package. Passthrough is the device camera composite and is not a package.
 * Observatory and Design studio carry original geometry, lights, and occlusion volumes.
 */
data class EnvironmentPackage(
    val id: String,
    val name: String,
    val mood: String,
    val license: String,
    val redistribution: String,
    val lod: List<LodLevel>,
    val lights: List<SceneLight>,
    val occlusion: List<OcclusionVolume>,
    val frameBudgetMs: Float,
    val passthrough: Boolean,
    val skyHex: String,
    val welcomeHex: String,
    val locomotionForced: Boolean = false,
) {
    val bundled: Boolean get() = redistribution == "curated-channel" || redistribution == "device-composite"
    val backgroundHex: String get() = skyHex

    init {
        require(name.isNotBlank() && name.length <= 60) { "Invalid environment name" }
        require(mood.isNotBlank() && mood.length <= 40) { "Invalid environment mood" }
        require(license.isNotBlank() && license.length <= 400) { "Environment is missing a license" }
        require(!locomotionForced) { "Environment failed the comfort gate" }
        require(kotlin.math.abs(frameBudgetMs - EnvironmentCatalog.FRAME_BUDGET_MS) < 0.05f) { "Environment missed the frame-time gate" }
        require(HEX.matches(skyHex) && HEX.matches(welcomeHex)) { "Invalid environment color" }
        if (passthrough) {
            require(id == "passthrough" && redistribution == "device-composite") { "Passthrough is not a downloaded package" }
            require(lod.isEmpty() && lights.isEmpty() && occlusion.isEmpty()) { "Passthrough does not load a scene package" }
        } else {
            require(id in CuratedChannel.IDS) { "Unknown environment. There is no environment marketplace." }
            require(redistribution == "curated-channel") { "Environment is not on the curated channel" }
            require(lod.size >= 2 && lod.map { it.level }.distinct().size == lod.size) { "Environment needs more than one level of detail" }
            require(lights.isNotEmpty() && lights.size <= 4) { "Environment is missing lighting" }
            require(occlusion.isNotEmpty() && occlusion.size <= 4) { "Environment is missing occlusion" }
        }
    }
}

interface EnvironmentPackageSource {
    fun fetch(id: String): ByteArray
}

object CuratedChannel : EnvironmentPackageSource {
    val IDS = setOf("observatory", "design-studio")

    override fun fetch(id: String): ByteArray {
        require(!id.contains("://") && !id.contains("/") && !id.contains(" ")) { "No environment marketplace" }
        val pkg = definition(id)
        return EnvironmentPackages.encode(pkg)
    }

    fun definition(id: String): EnvironmentPackage = when (id) {
        "observatory" -> observatory()
        "design-studio" -> designStudio()
        else -> error("Unknown environment. There is no environment marketplace.")
    }

    private fun observatory() = EnvironmentPackage(
        id = "observatory",
        name = "Calm observatory",
        mood = "calm-dark",
        license = "Original Luma environment. No third-party meshes or textures. Redistribution only on the curated channel.",
        redistribution = "curated-channel",
        lod = listOf(
            LodLevel(0, 12f, 6f, listOf(
                ScenePrimitive("sphere", 8f, "#10182A"),
                ScenePrimitive("cylinder", 3.2f, "#1A2436"),
                ScenePrimitive("sphere", 0.15f, "#F2E2B8"),
            )),
            LodLevel(1, 40f, 2f, listOf(ScenePrimitive("sphere", 8f, "#10182A"))),
        ),
        lights = listOf(
            SceneLight("ambient", "#2A241C", 0.18f, Vec3(0f, 2f, 0f)),
            SceneLight("point", "#F2C572", 0.55f, Vec3(0f, 2.4f, -1.2f)),
        ),
        occlusion = listOf(
            OcclusionVolume("box", Vec3(0f, -0.2f, 0f), 6f),
            OcclusionVolume("sphere", Vec3(0f, 1.2f, 0f), 8f),
        ),
        frameBudgetMs = EnvironmentCatalog.FRAME_BUDGET_MS,
        passthrough = false,
        skyHex = "#070B16",
        welcomeHex = "#10182A",
    )

    private fun designStudio() = EnvironmentPackage(
        id = "design-studio",
        name = "Bright design studio",
        mood = "bright-studio",
        license = "Original Luma environment. No third-party meshes or textures. Redistribution only on the curated channel.",
        redistribution = "curated-channel",
        lod = listOf(
            LodLevel(0, 10f, 7f, listOf(
                ScenePrimitive("box", 6f, "#F7F4EE"),
                ScenePrimitive("box", 1.4f, "#D7C4A3"),
                ScenePrimitive("quad", 2.2f, "#E7EEF6"),
                ScenePrimitive("box", 4f, "#F4F7FB"),
            )),
            LodLevel(1, 36f, 2.5f, listOf(
                ScenePrimitive("box", 6f, "#F7F4EE"),
                ScenePrimitive("box", 4f, "#F4F7FB"),
            )),
        ),
        lights = listOf(
            SceneLight("ambient", "#F7F4EE", 0.45f, Vec3(0f, 2.5f, 0f)),
            SceneLight("point", "#FFFFFF", 0.8f, Vec3(-1.2f, 2.6f, -0.4f)),
            SceneLight("point", "#D6E4F0", 0.35f, Vec3(1.4f, 2.2f, 0.6f)),
        ),
        occlusion = listOf(
            OcclusionVolume("box", Vec3(0f, -0.15f, 0f), 6f),
            OcclusionVolume("box", Vec3(0f, 1.5f, -3f), 4f),
        ),
        frameBudgetMs = EnvironmentCatalog.FRAME_BUDGET_MS,
        passthrough = false,
        skyHex = "#E7EEF6",
        welcomeHex = "#F7F4EE",
    )
}

object EnvironmentPackages {
    private val ALLOWED = setOf(
        "id", "name", "mood", "license", "redistribution", "lod", "lights", "occlusion",
        "frameBudgetMs", "skyHex", "welcomeHex", "locomotionForced",
    )

    fun encode(pkg: EnvironmentPackage): ByteArray {
        require(!pkg.passthrough) { "Passthrough is not a downloaded package" }
        val body = body(pkg).toString().toByteArray(Charsets.UTF_8)
        val header = "LUMAENV 1\n${sha256Hex(body)}\n".toByteArray(Charsets.US_ASCII)
        return header + body
    }

    fun decode(bytes: ByteArray): EnvironmentPackage {
        require(bytes.size in 64..512_000) { "Environment package is the wrong size" }
        val first = bytes.indexOfByte('\n'.code.toByte())
        val second = bytes.indexOfByte('\n'.code.toByte(), first + 1)
        require(first > 0 && second > first) { "Environment package is missing its header" }
        require(String(bytes, 0, first, Charsets.US_ASCII) == "LUMAENV 1") { "Unknown environment package" }
        val hash = String(bytes, first + 1, second - first - 1, Charsets.US_ASCII)
        val body = bytes.copyOfRange(second + 1, bytes.size)
        require(hash == sha256Hex(body)) { "Environment package failed its integrity check" }
        val json = JSONObject(String(body, Charsets.UTF_8))
        val names = json.names() ?: JSONArray()
        for (index in 0 until names.length()) {
            require(names.getString(index) in ALLOWED) { "No environment marketplace" }
        }
        return fromJson(json)
    }

    private fun body(pkg: EnvironmentPackage) = JSONObject()
        .put("id", pkg.id)
        .put("name", pkg.name)
        .put("mood", pkg.mood)
        .put("license", pkg.license)
        .put("redistribution", pkg.redistribution)
        .put("lod", JSONArray().apply { pkg.lod.sortedBy { it.level }.forEach { put(lodJson(it)) } })
        .put("lights", JSONArray().apply { pkg.lights.forEach { put(lightJson(it)) } })
        .put("occlusion", JSONArray().apply { pkg.occlusion.forEach { put(occlusionJson(it)) } })
        .put("frameBudgetMs", pkg.frameBudgetMs.toDouble())
        .put("skyHex", pkg.skyHex)
        .put("welcomeHex", pkg.welcomeHex)
        .put("locomotionForced", pkg.locomotionForced)

    private fun fromJson(json: JSONObject) = EnvironmentPackage(
        id = json.getString("id"),
        name = json.getString("name"),
        mood = json.getString("mood"),
        license = json.getString("license"),
        redistribution = json.getString("redistribution"),
        lod = json.getJSONArray("lod").let { array -> List(array.length()) { lod(array.getJSONObject(it)) } },
        lights = json.getJSONArray("lights").let { array -> List(array.length()) { light(array.getJSONObject(it)) } },
        occlusion = json.getJSONArray("occlusion").let { array -> List(array.length()) { occlusion(array.getJSONObject(it)) } },
        frameBudgetMs = json.getDouble("frameBudgetMs").toFloat(),
        passthrough = false,
        skyHex = json.getString("skyHex"),
        welcomeHex = json.getString("welcomeHex"),
        locomotionForced = json.getBoolean("locomotionForced"),
    )

    private fun lodJson(level: LodLevel) = JSONObject()
        .put("level", level.level)
        .put("maxDistance", level.maxDistance.toDouble())
        .put("costMs", level.costMs.toDouble())
        .put("primitives", JSONArray().apply { level.primitives.forEach { put(primitiveJson(it)) } })

    private fun primitiveJson(primitive: ScenePrimitive) = JSONObject()
        .put("shape", primitive.shape).put("size", primitive.size.toDouble()).put("color", primitive.color)

    private fun lightJson(light: SceneLight) = JSONObject()
        .put("kind", light.kind).put("color", light.color).put("intensity", light.intensity.toDouble()).put("position", vecArray(light.position))

    private fun occlusionJson(volume: OcclusionVolume) = JSONObject()
        .put("shape", volume.shape).put("center", vecArray(volume.center)).put("size", volume.size.toDouble())

    private fun lod(json: JSONObject) = LodLevel(
        json.getInt("level"),
        json.getDouble("maxDistance").toFloat(),
        json.getDouble("costMs").toFloat(),
        json.getJSONArray("primitives").let { array -> List(array.length()) { primitive(array.getJSONObject(it)) } },
    )

    private fun primitive(json: JSONObject) = ScenePrimitive(json.getString("shape"), json.getDouble("size").toFloat(), json.getString("color"))

    private fun light(json: JSONObject) = SceneLight(
        json.getString("kind"), json.getString("color"), json.getDouble("intensity").toFloat(), vec(json.getJSONArray("position")),
    )

    private fun occlusion(json: JSONObject) = OcclusionVolume(json.getString("shape"), vec(json.getJSONArray("center")), json.getDouble("size").toFloat())

    private fun vecArray(value: Vec3) = JSONArray().put(value.x.toDouble()).put(value.y.toDouble()).put(value.z.toDouble())

    private fun vec(array: JSONArray): Vec3 {
        require(array.length() == 3) { "Invalid environment vector" }
        return Vec3(array.getDouble(0).toFloat(), array.getDouble(1).toFloat(), array.getDouble(2).toFloat())
    }
}

class EnvironmentInstaller(private val source: EnvironmentPackageSource) {
    private val installed = linkedMapOf<String, EnvironmentPackage>()

    fun installed(id: String): EnvironmentPackage? = installed[id]

    fun download(id: String): EnvironmentPackage {
        require(id in CuratedChannel.IDS) { "Unknown environment. There is no environment marketplace." }
        val bytes = source.fetch(id)
        val pkg = EnvironmentPackages.decode(bytes)
        require(pkg.id == id) { "Environment package does not match the requested scene" }
        installed[id] = pkg
        return pkg
    }

    fun activate(id: String, measuredFrameMs: Float?): EnvironmentSelection {
        if (id == "passthrough") {
            return EnvironmentSelection(EnvironmentCatalog.PASSTHROUGH, null, false, "Passthrough is on. The room stays visible.")
        }
        val pkg = installed[id] ?: download(id)
        if (measuredFrameMs != null && measuredFrameMs > pkg.frameBudgetMs) {
            return EnvironmentSelection(
                EnvironmentCatalog.PASSTHROUGH, null, true,
                "Frame time exceeded ${pkg.frameBudgetMs} ms. Staying in passthrough.",
            )
        }
        val headroom = if (measuredFrameMs == null) pkg.frameBudgetMs else (pkg.frameBudgetMs - measuredFrameMs).coerceAtLeast(0f)
        val lod = pkg.lod.sortedBy { it.level }.firstOrNull { it.costMs <= headroom }
            ?: return EnvironmentSelection(
                EnvironmentCatalog.PASSTHROUGH, null, true,
                "No level of detail fits the frame budget. Staying in passthrough.",
            )
        return EnvironmentSelection(
            pkg, lod, false,
            "${pkg.name}. ${pkg.license} LOD ${lod.level}, ${pkg.lights.size} lights, ${pkg.occlusion.size} occlusion volumes.",
        )
    }
}

/** Reads one curated package from a shelf. The shelf is not a marketplace. */
class SocketPackageSource(private val host: String, private val port: Int) : EnvironmentPackageSource {
    var fetches: Int = 0
        private set

    override fun fetch(id: String): ByteArray {
        require(id in CuratedChannel.IDS) { "Unknown environment. There is no environment marketplace." }
        require(port in 1..65535) { "Invalid environment shelf" }
        fetches += 1
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2_000)
            socket.soTimeout = 2_000
            val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
            writer.write("GET $id\n")
            writer.flush()
            val input = socket.getInputStream()
            val header = readAsciiLine(input)
            if (!header.startsWith("OK ")) error("Unknown environment. There is no environment marketplace.")
            val size = header.removePrefix("OK ").toIntOrNull() ?: error("Environment package was truncated")
            require(size in 1..512_000) { "Environment package is the wrong size" }
            val body = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(body, read, size - read)
                if (count < 0) error("Environment package was truncated")
                read += count
            }
            return body
        }
    }
}

/** Serves the curated packages once they have been published. Tests use the loopback address. */
class CuratedPackageShelf(files: Map<String, ByteArray>) : AutoCloseable {
    private val published = files.filterKeys { it in CuratedChannel.IDS }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    private val thread = Thread({
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            try {
                socket.soTimeout = 2_000
                val header = readAsciiLine(socket.getInputStream())
                val id = header.removePrefix("GET ").trim()
                val bytes = if (header.startsWith("GET ") && !id.contains("://")) published[id] else null
                val out = socket.getOutputStream()
                if (bytes == null) out.write("ERR unknown\n".toByteArray(Charsets.US_ASCII))
                else {
                    out.write("OK ${bytes.size}\n".toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                }
                out.flush()
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }
    }, "environment-shelf").apply { isDaemon = true; start() }

    fun source() = SocketPackageSource("127.0.0.1", port)

    override fun close() {
        runCatching { server.close() }
        thread.join(1_000)
    }
}

class AssetEnvironmentSource(private val open: (String) -> ByteArray) : EnvironmentPackageSource {
    override fun fetch(id: String): ByteArray {
        require(id in CuratedChannel.IDS) { "Unknown environment. There is no environment marketplace." }
        return open(id)
    }
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private fun readAsciiLine(input: InputStream): String {
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) error("Environment shelf closed")
        if (next == '\n'.code) break
        out.write(next)
        require(out.size() < 200) { "Environment request is too large" }
    }
    return out.toString(Charsets.US_ASCII)
}

private fun ByteArray.indexOfByte(value: Byte, start: Int = 0): Int {
    for (index in start until size) if (this[index] == value) return index
    return -1
}

private val HEX = Regex("#[0-9A-Fa-f]{6}")
