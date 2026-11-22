package com.metamanning.questlite

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemManager
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.MeshCreationSystem
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** A world-space workspace marker. Call lifecycle and animation methods on the activity thread. */
internal class MindScene(
    private val systemManager: SystemManager,
    private val onRecall: () -> Unit,
) {
    private data class Orbit(
        val radius: Float,
        val orientation: Quaternion,
        val phase: Float,
        val speed: Float,
        val beadColor: Int,
        val satelliteColor: Int,
    ) {
        fun point(angle: Float) = Vector3(radius * cos(angle), radius * sin(angle), 0f)
    }

    private data class Track(
        val orbit: Orbit,
        val ring: Entity,
        val satellite: Entity,
        var pose: Pose,
    )

    private val orbits = listOf(
        Orbit(0.235f, tilt(0.40f, -0.33f), 0.52f, 0.65f, 0xFF30B7A8.toInt(), 0xFF8AFFF0.toInt()),
        Orbit(0.310f, tilt(1.03f, 0.70f), 2.35f, -0.49f, 0xFFCC903F.toInt(), 0xFFFFD679.toInt()),
        Orbit(0.390f, tilt(1.16f, -0.79f), 4.15f, 0.36f, 0xFF9267CE.toInt(), 0xFFD7B7FF.toInt()),
    )

    private val main = Handler(Looper.getMainLooper())
    private val entities = mutableListOf<Entity>()
    private val tracks = mutableListOf<Track>()
    private val meshUrls = mutableListOf<String>()
    private val meshManager get() = systemManager.findSystem<MeshCreationSystem>().meshManager
    private var core: Entity? = null
    private var coreObject: SceneObject? = null
    private var listenerId = 0L
    private var visible = true
    private var lastElapsed: Float? = null
    private var motionSeconds = 0f

    fun create(pose: Pose) {
        if (core != null) return
        val prefix = "mesh://quest-lite-mind/${UUID.randomUUID()}"
        try {
            val target = Entity.create(listOf(
                Mesh(Uri.parse("mesh://sphere"), MeshCollision.LineTest),
                Sphere(0.105f),
                Material().apply {
                    baseColor = Color4(0.19f, 0.86f, 0.76f, 1f)
                    metallic = 0.18f
                    roughness = 0.32f
                },
                Transform(pose),
                Visible(visible),
            )).also { entities.add(it) }
            core = target

            orbits.forEachIndexed { index, orbit ->
                val ringPose = pose * Pose(Vector3(0f), orbit.orientation)
                val ring = decoration("$prefix/ring-$index", ringPose) {
                    val beads = List(BEAD_COUNT) { i -> orbit.point(i * TAU / BEAD_COUNT) }
                    beadMesh(beads, 0.009f, orbit.beadColor)
                }
                val satellitePose = Pose(ringPose * orbit.point(orbit.phase), ringPose.q)
                val satellite = decoration("$prefix/satellite-$index", satellitePose) {
                    beadMesh(listOf(Vector3(0f)), 0.026f, orbit.satelliteColor)
                }
                tracks.add(Track(orbit, ring, satellite, ringPose))
            }

            systemManager.findSystem<SceneObjectSystem>().getSceneObject(target)?.thenAccept { sceneObject ->
                main.post {
                    if (core !== target) return@post
                    listenerId = sceneObject.addInputListener(object : InputListener {
                        override fun onClick(receiver: SceneObject, hitInfo: HitInfo, sourceOfInput: Entity) {
                            main.post { if (core === target && visible) onRecall() }
                        }
                    })
                    coreObject = sceneObject
                }
            }
        } catch (error: Exception) {
            destroy()
            throw error
        }
    }

    fun update(elapsedSeconds: Float, reducedMotion: Boolean) {
        if (core == null || !elapsedSeconds.isFinite()) return
        val previous = lastElapsed
        lastElapsed = elapsedSeconds
        if (previous == null || reducedMotion || !visible) return
        // Limit the first step after an activity pause while retaining the existing phase.
        val step = (elapsedSeconds - previous).coerceIn(0f, 0.1f)
        if (step == 0f) return
        motionSeconds += step
        tracks.forEach { positionSatellite(it) }
    }

    fun recenter(pose: Pose) {
        core?.setComponent(Transform(pose))
        tracks.forEach { track ->
            track.pose = pose * Pose(Vector3(0f), track.orbit.orientation)
            track.ring.setComponent(Transform(track.pose))
            positionSatellite(track)
        }
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        lastElapsed = null
        entities.forEach { it.setComponent(Visible(visible)) }
    }

    fun destroy() {
        core = null
        coreObject?.removeInputListener(listenerId)
        coreObject = null
        main.removeCallbacksAndMessages(null)
        entities.asReversed().forEach { it.destroy() }
        entities.clear()
        tracks.clear()
        meshUrls.forEach { meshManager.unregisterMeshCreator(it) }
        meshUrls.clear()
        lastElapsed = null
        motionSeconds = 0f
    }

    private fun positionSatellite(track: Track) {
        val angle = track.orbit.phase + motionSeconds * track.orbit.speed
        val position = track.pose * track.orbit.point(angle)
        track.satellite.setComponent(Transform(Pose(position, track.pose.q)))
    }

    private fun decoration(url: String, pose: Pose, buildMesh: () -> SceneMesh): Entity {
        meshManager.registerMeshCreator(url) { _, _ -> buildMesh() }
        meshUrls.add(url)
        return Entity.create(listOf(
            Mesh(Uri.parse(url), MeshCollision.NoCollision),
            Transform(pose),
            Visible(visible),
        )).also { entities.add(it) }
    }

    private companion object {
        const val BEAD_COUNT = 32
        const val TAU = (2.0 * Math.PI).toFloat()

        fun tilt(pitch: Float, roll: Float): Quaternion {
            val x = Quaternion(w = cos(pitch / 2f), x = sin(pitch / 2f), y = 0f, z = 0f)
            val z = Quaternion(w = cos(roll / 2f), x = 0f, y = 0f, z = sin(roll / 2f))
            return z * x
        }

        // Six vertices and eight triangles per bead, with one material section for the whole track.
        fun beadMesh(centers: List<Vector3>, radius: Float, color: Int): SceneMesh {
            val axes = floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, 0f, 0f, -1f)
            val faces = intArrayOf(0, 3, 2, 0, 4, 3, 0, 5, 4, 0, 2, 5, 1, 2, 3, 1, 3, 4, 1, 4, 5, 1, 5, 2)
            val verticesPerBead = axes.size / 3
            val vertexCount = centers.size * verticesPerBead
            val positions = FloatArray(vertexCount * 3)
            val normals = FloatArray(positions.size)
            val indices = IntArray(centers.size * faces.size)
            centers.forEachIndexed { bead, center ->
                val offset = bead * axes.size
                axes.copyInto(normals, offset)
                repeat(verticesPerBead) { vertex ->
                    val axis = vertex * 3
                    positions[offset + axis] = center.x + radius * axes[axis]
                    positions[offset + axis + 1] = center.y + radius * axes[axis + 1]
                    positions[offset + axis + 2] = center.z + radius * axes[axis + 2]
                }
                faces.forEachIndexed { index, faceVertex ->
                    indices[bead * faces.size + index] = bead * verticesPerBead + faceVertex
                }
            }
            val material = SceneMaterial(
                SceneTexture(Color.valueOf(color)), AlphaMode.OPAQUE, SceneMaterial.PHYSICALLY_BASED_SHADER,
            ).apply {
                setRoughnessMetallicness(0.44f, 0.12f)
                setUnlit(true)
            }
            return SceneMesh.meshWithMaterials(
                positions, normals, FloatArray(vertexCount * 2),
                IntArray(vertexCount) { Color.WHITE }, indices,
                intArrayOf(0, indices.size), arrayOf(material), false,
            )
        }
    }
}
