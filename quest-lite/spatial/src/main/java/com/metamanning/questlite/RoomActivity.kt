/*
 * Spatial panel, passthrough and room-loading patterns adapted from Meta Spatial SDK Samples.
 * Copyright (c) Meta Platforms, Inc. and affiliates. MIT; see assets/licenses/Meta-Samples.txt.
 * Metamanning additions: account panels, room-relative layouts and workspace controls.
 */
package com.metamanning.questlite

import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.*
import com.meta.spatial.core.*
import com.meta.spatial.core.Entity
import com.meta.spatial.mruk.*
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Immersive workspace. Persistent coordinates are relative to a recognized MRUK room entity. */
class RoomActivity : AppSystemActivity() {
    private lateinit var mruk: MRUKFeature
    private var room: MRUKRoom? = null
    private var roomName = "Room"
    private var roomSaveBlocked = false
    private var sceneReady = false
    private var shuttingDown = false
    private var active = false
    private val placementHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val documentIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var managerEntity: Entity? = null
    private var controls: LinearLayout? = null
    private var roomStatus: TextView? = null
    private var message = "Luma is starting…"
    private val windows = mutableMapOf<Int, OpenWindow>()
    private val brain = SecondBrain()
    private val motion = ThoughtMotion()
    private val sceneLive = AtomicBoolean(false)
    private val controllerEdges = java.util.concurrent.ConcurrentLinkedQueue<ControllerEdge>()
    private val tetherBeads = mutableListOf<Entity>()
    private val linkBars = mutableListOf<Entity>()
    private var landingMarker: Entity? = null
    private var propsFailed = false
    private var onArrived: (() -> Unit)? = null
    private var commitWhenArrived: (() -> Unit)? = null
    private var tossInFlight: TossPreview? = null
    private val openedForMotion = mutableListOf<String>()
    private var stagingFocus: FocusBubble? = null
    private val focusTracks = mutableListOf<CardTrack>()
    private val windowHomes = linkedMapOf<String, Pose>()
    private var windowDimFrom = 0f
    private var windowDimTo = 0f
    private var motionClock = 0L
    private var returnMessage = "Canceled. The thoughts are back where they were."
    private val thoughtViews = mutableMapOf<Int, OpenThought>()
    private var thoughtStatus: TextView? = null
    private var environmentSwatch: TextView? = null
    private var environmentInstaller: EnvironmentInstaller? = null
    private var coThinkHost: CoThinkHost? = null
    private var scrollQuietUntil = 0L
    private val prefs by lazy { getSharedPreferences("room-workspaces-v1", MODE_PRIVATE) }
    private data class OpenWindow(var saved: WindowLayout, val entity: Entity, var follow: Boolean = false, var wasGrabbed: Boolean = false)
    private data class OpenThought(
        val thoughtId: String,
        val slot: Int,
        val entity: Entity,
        var root: View? = null,
        var titleView: TextView? = null,
        var detailView: TextView? = null,
        var stepsHost: LinearLayout? = null,
        var timerView: TextView? = null,
        var pauseButton: Button? = null,
        var focusButton: Button? = null,
    )
    private data class ControllerEdge(val origin: Vec3, val forward: Vec3, val pressed: Int)
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action != "$packageName.WORKSPACE_EVENT" || shuttingDown) return
            if (intent.getStringExtra("event") == "manager") { recallManager(); return }
            val window = windows.values.firstOrNull { it.saved.id == intent.getStringExtra("window_id") } ?: return
            when (intent.getStringExtra("event")) {
                "url" -> {
                    val url = intent.getStringExtra("url") ?: return
                    if ((window.saved.kind == "browser" && RoomLayoutCodec.safeBrowserUrl(url)) || (window.saved.kind == "assistant" && RoomLayoutCodec.safeUrl(url))) { window.saved = window.saved.copy(url = url); saveRoom() }
                }
                "closed" -> closeWindow(window.saved.slot)
                "new_browser" -> if (window.saved.kind == "assistant") addWindow("browser", requestedUrl = intent.getStringExtra("url") ?: "https://www.wikipedia.org/")
                "new_widget" -> if (window.saved.kind == "assistant") chooseWidget()
                "create_widget" -> if (window.saved.kind == "assistant") {
                    val html = intent.getStringExtra("html") ?: return
                    if (html.toByteArray(Charsets.UTF_8).size > RoomLayoutCodec.MAX_HTML_BYTES) { setMessage("Widget HTML exceeds 128 KB."); return }
                    AlertDialog.Builder(this@RoomActivity).setTitle("Create a widget from this AI reply?")
                        .setMessage("Run the extracted HTML in its own offline widget panel? You can edit the code and enable network access from Widget options.")
                        .setPositiveButton("Create widget") { _, _ -> addWindow("widget", widgetHtml = html) }.setNegativeButton("Cancel", null).show()
                }
                "widget_updated" -> if (window.saved.kind == "widget") {
                    val html = intent.getStringExtra("html") ?: return
                    if (html.toByteArray(Charsets.UTF_8).size <= RoomLayoutCodec.MAX_HTML_BYTES) {
                        window.saved = window.saved.copy(html = html, network = intent.getBooleanExtra("network", false)); saveRoom()
                    }
                }
                "assistant_draft" -> if (window.saved.kind == "browser") {
                    val prompt = intent.getStringExtra("prompt") ?: return
                    if (prompt.length <= 16000) routeAssistantDraft(prompt)
                }
            }
        }
    }
    override fun registerFeatures(): List<SpatialFeature> {
        mruk = MRUKFeature(this, systemManager)
        return listOf(VRFeature(this), mruk)
    }
    override fun registerPanels(): List<PanelRegistration> {
        val registrations = mutableListOf<PanelRegistration>(
            ViewPanelRegistration(MANAGER, dynamicViewCreator = { _, context -> buildControls(context) }, settingsCreator = {
                UIPanelSettings(shape = QuadShapeOptions(width = 0.95f, height = 1.1f), display = DpDisplayOptions(720f, 840f), style = PanelStyleOptions(R.style.MetamanningTheme))
            })
        )
        repeat(ThoughtGraph.MAX_VISIBLE_CARDS) { slot ->
            registrations += ViewPanelRegistration(THOUGHT_BASE + slot, dynamicViewCreator = { entity, context ->
                val openSlot = thoughtViews.entries.firstOrNull { it.value.entity.id == entity.id }?.key
                if (openSlot == null) TextView(context).apply { text = "Thought" } else buildThoughtCard(openSlot, context)
            }, settingsCreator = {
                UIPanelSettings(shape = QuadShapeOptions(width = 0.66f, height = 0.72f), display = DpDisplayOptions(680f, 760f), style = PanelStyleOptions(R.style.MetamanningTheme))
            })
        }
        repeat(RoomLayoutCodec.MAX_WINDOWS) { slot ->
            registrations += IntentPanelRegistration(CHAT_BASE + slot, intentCreator = {
                val saved = requireNotNull(windows[slot]).saved
                Intent(this, windowPanelActivity(saved.kind)).apply {
                    data = Uri.parse("metamanning://window/${saved.id}")
                    putExtra("spatial_panel", true); putExtra("window_id", saved.id)
                    putExtra("widget_html", saved.html); putExtra("widget_network", saved.network);
                    putExtra("window_title", saved.title); putExtra("initial_hidden", saved.hidden); putExtra("initial_url", saved.url)
                }
            }, settingsCreator = {
                AndroidSurfacePanelSettings(UIPanelSettings(shape = QuadShapeOptions(width = 1.1f, height = 0.9f), display = DpDisplayOptions(1000f, 820f), style = PanelStyleOptions(R.style.SpatialPanelTheme)))
            })
        }
        return registrations
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(events, IntentFilter("$packageName.WORKSPACE_EVENT"), RECEIVER_NOT_EXPORTED)
        systemManager.registerSystem(object : SystemBase() {
            private var last = 0L
            private var lastExecute = 0L
            private val queued = AtomicBoolean(false)
            override fun execute() {
                val now = SystemClock.uptimeMillis()
                val frameDt = if (lastExecute == 0L) 0f else (now - lastExecute).toFloat()
                lastExecute = now
                if (now - last < 33 || !queued.compareAndSet(false, true)) return
                last = now
                runCatching { sampleControllers() }
                runOnUiThread {
                    try {
                        if (frameDt > 0f) brain.frames.add(frameDt)
                        if (sceneReady && active && !shuttingDown) updateWorkspace()
                    } finally { queued.set(false) }
                }
            }
        })
    }
    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        val savedEnvironment = prefs.getString("environment", null)
        if (savedEnvironment == null) {
            scene.enablePassthrough(prefs.getBoolean("passthrough", true))
            brain.environmentId = "passthrough"
        } else {
            val selection = runCatching { environments().activate(savedEnvironment, null) }.getOrElse { environments().activate("passthrough", null) }
            brain.environmentId = selection.active.id
            brain.environmentLod = selection.lod?.level
            scene.enablePassthrough(selection.active.passthrough)
        }
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        loadBrain()
        sceneReady = true
        val menu = frontPose(-1.45f, 1.6f)
        menu.t.y -= 0.35f
        managerEntity = Entity.create(listOf(Panel(MANAGER), Transform(menu), Grabbable()))
        presentOpeningScene()
        sceneLive.set(true)
        if (checkSelfPermission(SCENE_PERMISSION) == PackageManager.PERMISSION_GRANTED) loadRooms()
        else requestPermissions(arrayOf(SCENE_PERMISSION), SCENE_REQUEST)
    }
    private fun loadRooms() {
        setMessage("Recognizing your mapped room…")
        mruk.loadSceneFromDevice().whenComplete { result, error -> runOnUiThread {
            if (shuttingDown) return@runOnUiThread
            setMessage(if (error != null || result != MRUKLoadDeviceResult.SUCCESS)
                "Temporary workspace is ready. Room saving is unavailable; try Set up room or Reload room."
            else "Workspace is ready. Look around to recognize your room for saved placements.")
        } }
    }
    private fun updateWorkspace() {
        val viewer = scene.getViewerPose()
        // getCurrentRoom() can fall back to the first room. Never restore private windows on that fallback.
        val recognized = mruk.rooms.firstOrNull { it.entity != null && it.isPositionInRoom(viewer.t) }
        if (recognized?.anchor?.uuid != room?.anchor?.uuid || recognized?.entity?.id != room?.entity?.id) changeRoom(recognized)
        var released = false
        for (window in windows.values) {
            if (window.follow && !window.saved.hidden && brain.focus == null && stagingFocus == null) {
                val target = roomLocal(frontPose(0f, 1.3f))
                val current = window.entity.getComponent<Transform>().transform
                window.entity.setComponent(Transform(current.lerp(target, 0.08f)))
            }
            val grabbed = window.entity.tryGetComponent<Grabbable>()?.isGrabbed == true
            if (window.wasGrabbed && !grabbed) released = true
            window.wasGrabbed = grabbed
        }
        if (released) saveRoom()
        stepSceneMotion()
        drainControllers()
        syncDesk(announce = true)
        syncLinks()
        if (brain.environmentId != "passthrough" && brain.frames.exceeded()) selectEnvironment("passthrough")
    }
    private fun changeRoom(next: MRUKRoom?) {
        // Do not persist unlocalized transforms, and do not show one room's conversations in another.
        val temporary = if (room == null && next != null) windows.values.map { window ->
            val pose = snapshot(window)
            window.saved.copy(position = pose.position, rotation = pose.rotation)
        } else emptyList()
        if (room != null) saveRoom()
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        room = next; roomSaveBlocked = false
        if (next == null) {
            setMessage("Passthrough is on. The thoughts stay in front of you.")
            refreshControls(); relayoutThoughts(); return
        }
        val roomId = next.anchor.uuid.toString()
        val saved = prefs.getString("layout.$roomId", null)
        if (saved != null) {
            runCatching { RoomLayoutCodec.decode(saved, roomId) }.onSuccess { layout ->
                roomName = layout.name
                layout.windows.forEach { createWindow(it) }
                setMessage(if (layout.windows.isEmpty()) "$roomName recognized. Thoughts stay in front of you."
                    else "$roomName · restored ${layout.windows.size} room windows")
            }.onFailure {
                roomSaveBlocked = true; roomName = prefs.getString("name.$roomId", "Room") ?: "Room"
                setMessage("Saved layout could not be restored. It has been preserved. Import a backup or explicitly reset this room.")
            }
        } else {
            roomName = prefs.getString("name.$roomId", "Room") ?: "Room"
            if (temporary.isNotEmpty()) {
                temporary.forEach { saved ->
                    val world = Pose(Vector3(saved.position[0], saved.position[1], saved.position[2]),
                        Quaternion(saved.rotation[0], saved.rotation[1], saved.rotation[2], saved.rotation[3]))
                    val local = roomLocal(world)
                    createWindow(saved.copy(position = listOf(local.t.x, local.t.y, local.t.z),
                        rotation = listOf(local.q.w, local.q.x, local.q.y, local.q.z)))
                }
                saveRoom()
                setMessage("$roomName recognized. Your workspace is now saved in this room.")
            } else {
                setMessage("$roomName recognized. Thoughts stay in front of you.")
            }
        }
        refreshControls()
        relayoutThoughts()
    }
    private fun roomPose(): Pose = room?.entity?.getComponent<Transform>()?.transform ?: error("No recognized room")
    private fun roomLocal(world: Pose): Pose = if (room == null) world else roomPose().inverse() * world
    private fun frontPose(side: Float = 0f, distance: Float = 1.3f): Pose {
        val head = scene.getViewerPose().removePitchAndRoll()
        val result = head * Pose(Vector3(side, -ReadingFrame.EYE_DROP_M, distance))
        val direction = Vector3(result.t.x - head.t.x, 0f, result.t.z - head.t.z)
        result.q = Quaternion.lookRotation(direction)
        return result
    }
    private fun browserDialog() {
        val field = EditText(this).apply { hint = "https://example.com"; isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle("Open a workspace browser").setView(field).setPositiveButton("Open") { _, _ ->
            val input = field.text.toString().trim()
            val url = if (input.isEmpty()) "https://www.wikipedia.org/" else if (input.contains(" ") || !input.contains(".")) "https://www.google.com/search?q=" + Uri.encode(input) else if (input.contains("://")) input else "https://$input"
            addWindow("browser", requestedUrl = url)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun chooseWidget() {
        runCatching {
            val templates = assets.open("widget-templates.json").bufferedReader().use { org.json.JSONArray(it.readText()) }
            val titles = Array(templates.length() + 1) { index -> if (index == 0) "Blank / paste HTML" else templates.getJSONObject(index - 1).getString("title") }
            AlertDialog.Builder(this).setTitle("Create an HTML widget").setItems(titles) { _, which ->
                val html = if (which == 0) "<h1>New widget</h1><p>Choose Edit to paste HTML, or Options to import an HTML file.</p>" else templates.getJSONObject(which - 1).getString("html")
                addWindow("widget", widgetHtml = html)
            }.show()
        }.onFailure { setMessage("Widget templates could not be loaded.") }
    }
    private fun routeAssistantDraft(prompt: String) {
        val assistants = windows.values.filter { it.saved.kind == "assistant" }
        if (assistants.isEmpty()) { setMessage("Open an Assistant window first, then use Ask AI again."); return }
        fun deliver(window: OpenWindow) {
            window.saved = window.saved.copy(hidden = false); window.entity.setComponent(Visible(true)); control(window, "resume")
            sendBroadcast(Intent("$packageName.WORKSPACE_CONTROL").setPackage(packageName).putExtra("window_id", window.saved.id).putExtra("command", "draft").putExtra("prompt", prompt))
            setMessage("Page excerpt offered to ${window.saved.title}. Review its draft before sending."); saveRoom(); refreshControls()
        }
        if (assistants.size == 1) deliver(assistants.first())
        else AlertDialog.Builder(this).setTitle("Choose an assistant window").setItems(assistants.map { it.saved.title }.toTypedArray()) { _, index -> deliver(assistants[index]) }.show()
    }
    private fun workspaceOptions() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val limit = SeekBar(this).apply { max = RoomLayoutCodec.MAX_WINDOWS - 1; progress = prefs.getInt("window-limit", RoomLayoutCodec.MAX_WINDOWS) - 1 }
        val label = TextView(this).apply { text = "Window limit: ${limit.progress + 1}" }; column.addView(label); column.addView(limit)
        limit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { label.text = "Window limit: ${value + 1}" }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val passthrough = CheckBox(this).apply { text = "Show my surroundings (passthrough)"; isChecked = prefs.getBoolean("passthrough", true) }; column.addView(passthrough)
        AlertDialog.Builder(this).setTitle("Workspace options").setView(column).setPositiveButton("Save") { _, _ ->
            if (limit.progress + 1 < windows.size || windows.keys.any { it > limit.progress }) { setMessage("Close higher-numbered windows before lowering the window limit."); return@setPositiveButton }
            prefs.edit().putInt("window-limit", limit.progress + 1).putBoolean("passthrough", passthrough.isChecked).apply()
            if (passthrough.isChecked) {
                brain.environmentId = "passthrough"
                prefs.edit().putString("environment", "passthrough").apply()
            }
            scene.enablePassthrough(passthrough.isChecked)
            applyEnvironmentChrome()
            setMessage(if (passthrough.isChecked) "Passthrough is on." else "Surroundings are hidden. Observatory and Design studio are the curated scenes.")
        }.setNegativeButton("Cancel", null).show()
    }
    private fun createWindow(saved: WindowLayout) {
        val parent = room?.entity
        // Allocate the record before Panel creation; the intent creator reads the stable slot record.
        val entity = Entity.create(mutableListOf(Transform(Pose(Vector3(saved.position[0], saved.position[1], saved.position[2]),
            Quaternion(saved.rotation[0], saved.rotation[1], saved.rotation[2], saved.rotation[3]))),
            Scale(Vector3(saved.size)), Grabbable(enabled = !saved.locked), Visible(!saved.hidden)).apply {
            if (parent != null) add(TransformParent(parent))
        })
        windows[saved.slot] = OpenWindow(saved, entity)
        entity.setComponent(Panel(CHAT_BASE + saved.slot))
    }
    private fun addWindow(kind: String = "assistant", requestedUrl: String = "https://chatgpt.com/", widgetHtml: String = "") {
        val limit = prefs.getInt("window-limit", RoomLayoutCodec.MAX_WINDOWS).coerceIn(1, RoomLayoutCodec.MAX_WINDOWS)
        val slot = (0 until limit).firstOrNull { !windows.containsKey(it) }
        if (slot == null) { setMessage("Window limit reached. Close one or increase the limit in Workspace options."); return }
        if (kind == "browser" && !RoomLayoutCodec.safeBrowserUrl(requestedUrl)) { setMessage("Enter a valid HTTPS browser address."); return }
        val offsets = floatArrayOf(0f, 1.2f, -1.2f, 2.4f, -2.4f, 0f)
        val placed = frontPose(offsets[slot], ReadingArc.panelDistance())
        if (slot == 5) placed.t.y += 0.95f
        val pose = roomLocal(placed)
        val title = when (kind) { "browser" -> "Browser ${slot + 1}"; "widget" -> "Widget ${slot + 1}"; else -> "Conversation ${slot + 1}" }
        createWindow(WindowLayout(slot = slot, title = title, url = if (kind == "browser") requestedUrl else "https://chatgpt.com/", kind = kind, html = widgetHtml, position = listOf(pose.t.x, pose.t.y, pose.t.z), rotation = listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z)))
        saveRoom(); refreshControls()
        if (room == null) setMessage("Temporary workspace ready. Set up a room when you want windows to return here later.")
    }
    private fun snapshot(window: OpenWindow): WindowLayout {
        val pose = windowHomes[window.saved.id] ?: window.entity.getComponent<Transform>().transform
        return window.saved.copy(position = listOf(pose.t.x, pose.t.y, pose.t.z), rotation = listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z))
    }
    private fun currentLayout(): RoomLayout {
        val id = room?.anchor?.uuid?.toString() ?: error("Room is not recognized")
        // Follow mode is temporary; remember its last pinned room position, not moving head-relative poses.
        return RoomLayout(id, roomName, windows.values.sortedBy { it.saved.slot }.map { if (it.follow) it.saved else snapshot(it) })
    }
    private fun saveRoom(): Boolean {
        if (room == null || roomSaveBlocked || shuttingDown) return false
        return runCatching {
            val layout = currentLayout(); val data = RoomLayoutCodec.encode(layout)
            prefs.edit().putString("layout.${layout.roomId}", data).putString("name.${layout.roomId}", roomName).apply()
            windows.values.filter { !it.follow }.forEach { it.saved = snapshot(it) }
        }.onFailure { setMessage("Layout could not be saved: ${it.message}") }.isSuccess
    }
    private fun control(window: OpenWindow, command: String) {
        sendBroadcast(Intent("$packageName.WORKSPACE_CONTROL").setPackage(packageName).putExtra("window_id", window.saved.id).putExtra("command", command).putExtra("title", window.saved.title))
    }
    private fun destroyPanel(window: OpenWindow) { control(window, "close"); window.entity.destroy() }
    private fun closeWindow(slot: Int) {
        val window = windows[slot] ?: return
        val id = room?.anchor?.uuid?.toString()
        if (id != null) {
            val archiveResult = runCatching {
                val previous = prefs.getString("archive.$id", null)?.let { RoomLayoutCodec.decode(it, id).windows } ?: emptyList()
                val entry = if (window.follow) window.saved else snapshot(window)
                val archived = (previous.filter { it.id != entry.id } + entry).takeLast(RoomLayoutCodec.MAX_WINDOWS)
                // Archive slots are temporary list positions; restored windows receive a free live slot.
                val layout = RoomLayout(id, roomName, archived.mapIndexed { index, item -> item.copy(slot = index) })
                prefs.edit().putString("archive.$id", RoomLayoutCodec.encode(layout)).apply()
            }
            if (archiveResult.isFailure) { setMessage("This window could not be archived. Its layout has been preserved."); return }
        }
        windows.remove(slot); destroyPanel(window); saveRoom(); refreshControls()
    }
    private fun restoreClosedWindow() {
        val id = room?.anchor?.uuid?.toString() ?: return
        runCatching {
            val value = prefs.getString("archive.$id", null)
            if (value == null) { setMessage("No recently closed windows in this room."); return }
            val layout = RoomLayoutCodec.decode(value, id)
            if (layout.windows.isEmpty()) { setMessage("No recently closed windows in this room."); return }
            AlertDialog.Builder(this).setTitle("Recently closed in $roomName").setItems(layout.windows.map { it.title }.toTypedArray()) { _, index ->
                if (room?.anchor?.uuid?.toString() != id) { setMessage("Return to the same room to restore this window."); return@setItems }
                val limit = prefs.getInt("window-limit", RoomLayoutCodec.MAX_WINDOWS).coerceIn(1, RoomLayoutCodec.MAX_WINDOWS)
                val slot = (0 until limit).firstOrNull { !windows.containsKey(it) }
                if (slot == null) { setMessage("Close a window or increase the window limit first."); return@setItems }
                val saved = layout.windows[index]
                if (windows.values.any { it.saved.id == saved.id }) return@setItems
                createWindow(saved.copy(slot = slot, hidden = false))
                val remaining = layout.copy(windows = layout.windows.filter { it.id != saved.id }.mapIndexed { i, item -> item.copy(slot = i) })
                prefs.edit().putString("archive.$id", RoomLayoutCodec.encode(remaining)).apply(); saveRoom(); refreshControls()
            }.show()
        }.onFailure { setMessage("Closed-window archive could not be read.") }
    }
    private fun recallManager() {
        managerEntity?.setComponent(Transform(frontPose(0f, 0.9f)))
        managerEntity?.setComponent(Visible(true))
    }
    private fun setMessage(value: String) { message = value; roomStatus?.text = value }
    private fun buildControls(context: Context): View {
        val gap = ShellStyle.dp(context, 8)
        val scroll = ScrollView(context).apply { isFillViewport = true; setBackgroundColor(Color.parseColor("#0B1019")) }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldY ->
            if (scrollY != oldY) scrollQuietUntil = SystemClock.uptimeMillis() + 500
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ShellStyle.header(context, column)
        column.setPadding(ShellStyle.dp(context, 18), ShellStyle.dp(context, 10), ShellStyle.dp(context, 18), ShellStyle.dp(context, 24))
        val welcome = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ShellStyle.dp(context, 20), ShellStyle.dp(context, 18), ShellStyle.dp(context, 20), ShellStyle.dp(context, 20))
            background = ShellStyle.panel(context, "#152536", "#315266", 20)
        }
        welcome.addView(TextView(context).apply {
            text = "YOUR WORKSPACE"; textSize = 12f; letterSpacing = 0.12f
            setTextColor(Color.parseColor("#91F4D4"))
        })
        welcome.addView(TextView(context).apply {
            text = "Start anywhere."; textSize = 27f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, gap, 0, gap)
        })
        roomStatus = TextView(context).apply {
            text = message; textSize = 15f; setTextColor(Color.parseColor("#C4D6E8"))
        }
        welcome.addView(roomStatus)
        column.addView(welcome, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        fun section(title: String, detail: String) {
            column.addView(TextView(context).apply {
                text = title; textSize = 20f; setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, ShellStyle.dp(context, 16), 0, 0)
            })
            column.addView(TextView(context).apply {
                text = detail; textSize = 13f; setTextColor(Color.parseColor("#9FB4CA"))
                setPadding(0, 0, 0, gap)
            })
        }
        fun row(vararg actions: Pair<String, () -> Unit>) {
            val line = LinearLayout(context)
            actions.forEach { (label, action) -> line.addView(Button(context).apply {
                text = label; ShellStyle.button(context, this, label); textSize = 15f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ShellStyle.dp(context, 62), 1f).apply {
                marginStart = ShellStyle.dp(context, 3); marginEnd = ShellStyle.dp(context, 3); bottomMargin = gap
            }) }
            column.addView(line)
        }
        section("In reach", "Controller and menu move the thoughts in front of you")
        row("Pull here" to { selectedThought()?.let { pullThoughtHere(it.id) } }, "Recall" to { recallThoughts() })
        row("Fan out" to { selectedThought()?.let { fanOutNow(it.id) } }, "Focus" to {
            val id = brain.selectedId
            if (id == null) setMessage("Select a thought first.") else focusNow(id)
        })
        section("Open", "Floating panels you can move around you")
        row("+ Conversation" to { addWindow() })
        row("Browser" to { browserDialog() }, "HTML widget" to { chooseWidget() })
        section("Your windows", "Focus, move, resize or bring a panel back")
        controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(controls)
        row("Bring windows here" to {
            val ordered = windows.values.sortedBy { it.saved.slot }
            val widths = ordered.map { 1.1f * it.saved.size }
            var left = -(widths.sum() + (ordered.size - 1).coerceAtLeast(0) * 0.12f) / 2f
            ordered.forEachIndexed { index, window ->
                window.follow = false; window.saved = window.saved.copy(hidden = false)
                val side = left + widths[index] / 2f; left += widths[index] + 0.12f
                window.entity.setComponent(Transform(roomLocal(frontPose(side, ReadingArc.panelDistance()))))
                window.entity.setComponent(Visible(true)); window.entity.setComponent(Grabbable(enabled = !window.saved.locked)); control(window, "resume")
            }; saveRoom(); refreshControls()
        }, "Recenter menu" to { recallManager() })
        section("Second brain", "Cards move in the scene. A slings, B cancels, X tosses, Y fans or collapses, stick click focuses. The menu does the same. Head and hand rays stay off.")
        environmentSwatch = TextView(context).apply {
            textSize = 14f; setPadding(ShellStyle.dp(context, 12), ShellStyle.dp(context, 8), ShellStyle.dp(context, 12), ShellStyle.dp(context, 8))
        }
        column.addView(environmentSwatch, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        thoughtStatus = TextView(context).apply { textSize = 14f; setTextColor(Color.parseColor("#C4D6E8")); setPadding(0, 0, 0, gap) }
        column.addView(thoughtStatus)
        row("New thought" to { newThought() }, "Search" to { searchThoughts() })
        row("Sling" to { slingSelected() }, "Toss" to { tossSelected() })
        row("Fan out" to { fanOut() }, "Collapse" to { collapseSelected() })
        row("Focus" to { enterFocus() }, "Exit focus" to { exitFocus() })
        row("Pin to room" to { pinSelected() }, "Palace" to { showPalace() })
        row("Undo" to { undoThought() }, "History" to { showHistory() })
        row("Recall thoughts" to { recallThoughts() }, "Desk" to { showDesk() })
        row("Link" to { linkSelected() }, "Edit" to { editSelected() })
        row("New cluster" to { newCluster() }, "Passthrough" to { selectEnvironment("passthrough") })
        row("Observatory" to { selectEnvironment("observatory") }, "Design studio" to { selectEnvironment("design-studio") })
        row("Co-think" to { startCoThink() }, "Approve" to { approveShare() })
        row("Join share" to { joinCoThink() }, "Retrieval" to { retrieve() })
        row("Look and ask" to { lookAndAsk() }, "Build bench" to { buildBench() })
        applyEnvironmentChrome()
        section("Room & layout", "Optional: remember placements in this room")
        row("Set up room" to { mruk.requestSceneCapture().whenComplete { _, _ -> runOnUiThread { loadRooms() } } }, "Reload room" to { requestRoomPermission() })
        row("Workspace options" to { workspaceOptions() }, "Save room" to {
            if (room == null) setMessage("Set up a room before saving window positions.")
            else if (saveRoom()) setMessage("Saved $roomName. Windows will return when this room is recognized.")
        })
        row("Name room" to {
            if (room == null) setMessage("Set up a room before naming it.")
            else editName(roomName, "Room name") { roomName = it; saveRoom(); refreshControls() }
        }, "Closed windows" to {
            if (room == null) setMessage("Closed windows are available after room recognition.")
            else restoreClosedWindow()
        })
        row("Export layout" to { exportLayout() }, "Import layout" to { importLayout() })
        row("Reset this room" to {
            if (room == null) setMessage("No recognized room to reset.") else confirmReset()
        })
        scroll.addView(column); refreshControls(); return scroll
    }
    private fun refreshControls() {
        val column = controls ?: return
        column.removeAllViews()
        windows.values.sortedBy { it.saved.slot }.forEach { window ->
            val context = column.context
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ShellStyle.dp(context, 12), ShellStyle.dp(context, 10), ShellStyle.dp(context, 12), ShellStyle.dp(context, 12))
                background = ShellStyle.panel(context, "#121E2D", "#2C4054", 16)
            }
            val state = if (window.follow) "Follows you" else if (window.saved.hidden) "Hidden" else if (window.saved.locked) "Pinned" else if (room == null) "Temporary · movable" else "Room position · movable"
            line.addView(TextView(context).apply {
                text = window.saved.title; textSize = 18f; setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            line.addView(TextView(context).apply {
                text = "${window.saved.kind.replaceFirstChar { it.uppercase() }} · $state"; textSize = 13f
                setTextColor(Color.parseColor("#A5BDD1"))
                setPadding(0, 0, 0, ShellStyle.dp(context, 7))
            })
            val top = LinearLayout(context)
            val bottom = LinearLayout(context)
            fun action(parent: LinearLayout, label: String, operation: () -> Unit) {
                parent.addView(Button(context).apply {
                    text = label; ShellStyle.button(context, this, label); textSize = 13f
                    setOnClickListener { operation() }
                }, LinearLayout.LayoutParams(0, ShellStyle.dp(context, 56), 1f).apply {
                    marginStart = ShellStyle.dp(context, 2); marginEnd = ShellStyle.dp(context, 2)
                })
            }
            action(top, "Focus") {
                windows.values.forEach { other ->
                    val visible = other.saved.id == window.saved.id
                    other.saved = other.saved.copy(hidden = !visible); other.entity.setComponent(Visible(visible))
                    control(other, if (visible) "resume" else "pause")
                }; saveRoom(); refreshControls()
            }
            action(top, "Place") { placementOptions(window) }
            action(top, "Pull here") { pullWindowHere(window) }
            action(top, "Size") { sizeOptions(window) }
            action(bottom, if (window.saved.hidden) "Show" else "Hide") {
                window.saved = window.saved.copy(hidden = !window.saved.hidden); window.entity.setComponent(Visible(!window.saved.hidden))
                control(window, if (window.saved.hidden) "pause" else "resume"); saveRoom(); refreshControls()
            }
            action(bottom, "Rename") { editName(window.saved.title, "Window name") { window.saved = window.saved.copy(title = it); control(window, "rename"); saveRoom(); refreshControls() } }
            action(bottom, "Close") { closeWindow(window.saved.slot) }
            line.addView(top); line.addView(bottom)
            column.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ShellStyle.dp(context, 8) })
        }
        refreshThoughtStatus()
    }
    /**
     * A deliberate, controller-friendly precursor to a web-sling gesture: select a distant
     * panel in the workspace menu and pull it to reading distance. Do not pretend that a
     * hand-ray pinch is implemented here; genuine gesture recognition is a later device gate.
     * The original window is repositioned, never duplicated, and remains room-persistable.
     */
    private fun pullWindowHere(window: OpenWindow) {
        if (!active || !sceneReady || shuttingDown || windows[window.saved.slot]?.saved?.id != window.saved.id) return
        val decision = PullPolicy.decide(window.entity.tryGetComponent<Grabbable>()?.isGrabbed == true)
        if (!decision.moved) { setMessage(decision.reason); return }
        window.follow = false
        window.saved = window.saved.copy(hidden = false, locked = false)
        val target = roomLocal(frontPose(0f, PullPolicy.DISTANCE_M))
        window.entity.setComponent(Transform(target))
        window.entity.setComponent(Grabbable(enabled = true))
        window.entity.setComponent(Visible(true))
        control(window, "resume")
        if (decision.persist) saveRoom()
        refreshControls()
        setMessage("Pulled ${window.saved.title} into reach. Grab to place it, or pin it when ready.")
    }

    private fun placementOptions(window: OpenWindow) {
        val labels = arrayOf("In front of me", "To my left", "To my right", "Lower / desk height", "Follow me (temporary)", if (window.saved.locked) "Unlock movement" else "Pin and lock in this room", "Place at a surface I look at", "Pull this window to me")

        AlertDialog.Builder(this).setTitle(window.saved.title).setItems(labels) { _, choice ->
            if (choice == 6) {
                placeAtSurface(window); return@setItems
            }
            if (choice == 7) {
                pullWindowHere(window); return@setItems
            }
            if (choice == 5) {
                window.follow = false; window.saved = snapshot(window).copy(locked = !window.saved.locked)
                window.entity.setComponent(Grabbable(enabled = !window.saved.locked))
            } else {
                if (!window.follow) window.saved = snapshot(window)
                window.follow = choice == 4
                val world = frontPose(when (choice) {1 -> -1f; 2 -> 1f; else -> 0f})
                if (choice == 3) world.t.y -= 0.35f
                window.entity.setComponent(Transform(roomLocal(world)))
                window.entity.setComponent(Grabbable(enabled = !window.follow && !window.saved.locked))
            }
            saveRoom(); refreshControls()
        }.show()
    }
    private fun placeAtSurface(window: OpenWindow) {
        val expectedRoom = room?.anchor?.uuid ?: return
        setMessage("Look at a mapped wall or table. Placing this window in three seconds…")
        placementHandler.postDelayed({
            if (!active || !sceneReady || room?.anchor?.uuid != expectedRoom || windows[window.saved.slot]?.saved?.id != window.saved.id) return@postDelayed
            val viewer = scene.getViewerPose()
            val hit = mruk.raycastRoom(expectedRoom, viewer.t, viewer.q * Vector3.Forward, 5f)
            if (hit == null || hit.hitDistance < 0.5f) { setMessage("No suitable mapped surface found. Try another surface or use a placement preset."); return@postDelayed }
            val world = if (kotlin.math.abs(hit.hitNormal.y) < 0.6f) {
                Pose(hit.hitPosition + hit.hitNormal * 0.04f, Quaternion.lookRotation(-hit.hitNormal))
            } else {
                Pose(hit.hitPosition + Vector3(0f, 0.45f * window.saved.size + 0.04f, 0f), viewer.removePitchAndRoll().q)
            }
            window.follow = false
            window.entity.setComponent(Transform(roomLocal(world)))
            window.saved = snapshot(window).copy(locked = true)
            window.entity.setComponent(Grabbable(enabled = false)); saveRoom(); refreshControls()
            setMessage("Window pinned at the selected surface in $roomName.")
        }, 3000)
    }
    private fun sizeOptions(window: OpenWindow) {
        val slider = SeekBar(this).apply { max = 125; progress = ((window.saved.size - 0.55f) * 100).toInt() }
        AlertDialog.Builder(this).setTitle("Window size").setView(slider).setPositiveButton("Apply") { _, _ ->
            val size = 0.55f + slider.progress / 100f
            window.saved = window.saved.copy(size = size); window.entity.setComponent(Scale(Vector3(size))); saveRoom()
        }.setNegativeButton("Cancel", null).show()
    }
    private fun editName(initial: String, title: String, accept: (String) -> Unit) {
        val field = EditText(this).apply { setText(initial); filters = arrayOf(android.text.InputFilter.LengthFilter(60)); isSingleLine = true }
        AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("Save") { _, _ ->
            val value = field.text.toString().trim().filter { it.code >= 32 }
            if (value.isNotBlank()) accept(value)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun confirmReset() {
        val id = room?.anchor?.uuid?.toString() ?: return
        AlertDialog.Builder(this).setTitle("Reset $roomName?").setMessage("Remove only this room's saved window layout. ChatGPT conversations and other rooms stay in your account.")
            .setPositiveButton("Reset room") { _, _ ->
                windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
                prefs.edit().remove("layout.$id").apply(); roomSaveBlocked = false
                setMessage("Room layout reset. Add windows to arrange this room again."); refreshControls()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun requestRoomPermission() {
        if (checkSelfPermission(SCENE_PERMISSION) == PackageManager.PERMISSION_GRANTED) loadRooms()
        else requestPermissions(arrayOf(SCENE_PERMISSION), SCENE_REQUEST)
    }
    private fun exportLayout() {
        if (room == null) { setMessage("Recognize a room before exporting its layout."); return }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "luma-room.json"), EXPORT)
    }
    private fun importLayout() {
        if (room == null) { setMessage("Recognize the destination room first."); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), IMPORT)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val expectedRoom = room?.anchor?.uuid?.toString() ?: return
        val exportData = if (requestCode == EXPORT) runCatching { RoomLayoutCodec.encode(currentLayout()) }.getOrElse { setMessage("Layout could not be prepared: ${it.message}"); return } else null
        documentIo.execute {
            runCatching {
                if (requestCode == EXPORT) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportData) } ?: error("Cannot write layout")
                    runOnUiThread { if (!shuttingDown) setMessage("Room layout exported.") }
                }
                if (requestCode == IMPORT) {
                    val json = contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(RoomLayoutCodec.MAX_BYTES + 1)
                        require(bytes.size <= RoomLayoutCodec.MAX_BYTES) { "Layout exceeds size limit" }; String(bytes, Charsets.UTF_8)
                    } ?: error("Cannot read layout")
                    val layout = RoomLayoutCodec.offlineImport(RoomLayoutCodec.decode(json, expectedRoom))
                    runOnUiThread {
                        if (shuttingDown || room?.anchor?.uuid?.toString() != expectedRoom) return@runOnUiThread
                        AlertDialog.Builder(this).setTitle("Restore ${layout.name}?")
                            .setMessage("Replace this room's arrangement with ${layout.windows.size} saved windows? Imported HTML widgets run in their sandbox and start with network access off.")
                            .setPositiveButton("Restore") { _, _ ->
                                if (room?.anchor?.uuid?.toString() != expectedRoom) { setMessage("Room changed. Import again in the correct room."); return@setPositiveButton }
                                windows.values.toList().forEach { destroyPanel(it) }; windows.clear(); roomName = layout.name; roomSaveBlocked = false
                                layout.windows.forEach { createWindow(it) }; saveRoom(); refreshControls()
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            }.onFailure { runOnUiThread { if (!shuttingDown) setMessage("Layout operation failed: ${it.message}") } }
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == SCENE_REQUEST && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadRooms()
        else if (code == SCENE_REQUEST) setMessage("Room permission denied. Enable Spatial Data permission to use room persistence.")
    }
    override fun onResume() {
        super.onResume()
        active = true
        if (sceneReady) recallManager()
        windows.values.filter { !it.saved.hidden }.forEach { control(it, "resume") }
    }
    override fun onPause() {
        active = false
        placementHandler.removeCallbacksAndMessages(null)
        abandonMotion()
        saveRoom(); saveBrain(); saveDesk()
        windows.values.forEach { control(it, "pause") }
        super.onPause()
    }
    override fun onSpatialShutdown() {
        sceneLive.set(false)
        runCatching { coThinkHost?.close() }; coThinkHost = null
        abandonMotion()
        saveRoom(); saveBrain(); saveDesk(); shuttingDown = true; sceneReady = false
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        tetherBeads.forEach { runCatching { it.destroy() } }
        tetherBeads.clear()
        linkBars.forEach { runCatching { it.destroy() } }
        linkBars.clear()
        runCatching { landingMarker?.destroy() }
        landingMarker = null
        super.onSpatialShutdown()
    }
    override fun onDestroy() {
        runCatching { coThinkHost?.close() }; coThinkHost = null
        documentIo.shutdownNow(); runCatching { unregisterReceiver(events) }; super.onDestroy()
    }
    private fun brainProvenance(note: String) = Provenance("user", "local", System.currentTimeMillis(), note)

    private fun presentOpeningScene() {
        if (brain.environmentId == "passthrough") scene.enablePassthrough(true)
        if (brain.graph.thoughts().isEmpty()) {
            runCatching { OpeningScene.seed(brain.graph) { brainProvenance(it) } }
                .onSuccess { saveBrain() }
                .onFailure { setMessage(it.message ?: "The opening thoughts could not be created."); return }
        }
        val ids = OpeningScene.cardsToOpen(brain.graph)
        val placed = ids.mapNotNull { brain.graph.find(it) }
        if (OpeningScene.stacked(placed.map { it.coordinates })) {
            val spots = OpeningScene.spread(placed.size)
            placed.forEachIndexed { index, thought ->
                runCatching { brain.graph.move(thought.id, spots[index], brainProvenance("opening")) }
            }
            saveBrain()
        }
        ids.forEach { openThoughtCard(it) }
        brain.selectedId = ids.firstOrNull()
        setMessage("Passthrough is on. The note, steps, timer, and arc, grid, or stack are on each thought. Pull here draws it in.")
        loadDesk()
        syncDesk(false)
    }

    private fun loadBrain() {
        val saved = prefs.getString(BRAIN_PREF, null) ?: return
        runCatching { brain.loadJson(saved) }.onFailure { setMessage("Saved thoughts were preserved but could not be read.") }
    }

    private fun saveBrain() {
        if (shuttingDown) return
        runCatching { prefs.edit().putString(BRAIN_PREF, ThoughtGraph.encode(brain.graph)).apply() }
            .onFailure { setMessage("Thoughts could not be saved: ${it.message}") }
    }

    private fun loadDesk() {
        val saved = prefs.getString(DESK_PREF, null) ?: return
        runCatching { brain.desk = FocusClock.decode(saved) }.onFailure {
            prefs.edit().remove(DESK_PREF).apply()
            setMessage("The focus timer was reset. Its saved state could not be read.")
        }
    }

    private fun saveDesk() {
        if (shuttingDown) return
        runCatching { prefs.edit().putString(DESK_PREF, FocusClock.encode(brain.desk)).apply() }
    }

    private var shownDesk = ""

    private fun syncDesk(announce: Boolean) {
        val now = System.currentTimeMillis()
        val tick = FocusClock.catchUp(brain.desk, now)
        if (tick.advanced > 0) {
            brain.desk = tick.timer
            saveDesk()
            if (announce) setMessage(FocusClock.advancedMessage(brain.desk))
        }
        val label = FocusClock.label(brain.desk, now)
        if (label != shownDesk) {
            shownDesk = label
            refreshThoughtStatus()
            val pause = if (brain.desk.state == FocusRun.PAUSED) "Resume" else "Pause"
            thoughtViews.values.forEach { open ->
                open.timerView?.text = label
                open.pauseButton?.text = pause
            }
        }
    }

    private fun selectedThought(): Thought? {
        val id = brain.selectedId ?: run { setMessage("Select a thought first."); return null }
        return brain.graph.find(id) ?: run { brain.selectedId = null; setMessage("That thought is no longer in the library."); return null }
    }

    private fun refreshThoughtStatus() {
        val selected = brain.selectedId?.let { brain.graph.find(it) }
        val env = activeEnvironment()
        val flags = listOfNotNull(
            env?.name,
            if (brain.desk.phase == FocusPhase.IDLE && brain.desk.completedSessions == 0) null else FocusClock.label(brain.desk, System.currentTimeMillis()),
            if (brain.look.indicatorVisible) "CAPTURE ON" else null,
            if (brain.focus != null) "Focus" else null,
            brain.share?.let { if (it.role == SessionRole.HOST) "Host" else "Guest" },
        ).joinToString(" · ")
        val who = if (selected == null) "No thought selected" else "${selected.title} · ${selected.type.name.lowercase()} · ${selected.privacy.name.lowercase()}"
        thoughtStatus?.text = "$flags\n$who"
    }

    private fun environments(): EnvironmentInstaller {
        environmentInstaller?.let { return it }
        val created = EnvironmentInstaller(AssetEnvironmentSource { id -> assets.open("environments/$id.lumaenv").use { it.readBytes() } })
        environmentInstaller = created
        return created
    }

    private fun activeEnvironment(): EnvironmentPackage? {
        val id = brain.environmentId
        if (id == "passthrough") return EnvironmentCatalog.PASSTHROUGH
        return environments().installed(id) ?: runCatching { CuratedChannel.definition(id) }.getOrNull()
    }

    private fun applyEnvironmentChrome() {
        val env = activeEnvironment() ?: return
        val lod = brain.environmentLod?.let { " · LOD $it" }.orEmpty()
        environmentSwatch?.text = env.name + lod
        environmentSwatch?.setBackgroundColor(Color.parseColor(env.backgroundHex))
        val color = Color.parseColor(env.backgroundHex)
        val bright = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3 > 160
        environmentSwatch?.setTextColor(Color.parseColor(if (bright) "#1B2430" else "#F2F6FC"))
    }

    private fun selectEnvironment(id: String) {
        val measured = if (id != "passthrough" && brain.frames.ready()) brain.frames.average() else null
        val selection = runCatching { environments().activate(id, measured) }.getOrElse { setMessage(it.message ?: "That environment is not available."); return }
        brain.environmentId = selection.active.id
        brain.environmentLod = selection.lod?.level
        if (sceneReady) scene.enablePassthrough(selection.active.passthrough)
        prefs.edit().putString("environment", selection.active.id).putBoolean("passthrough", selection.active.passthrough).apply()
        applyEnvironmentChrome(); refreshThoughtStatus(); setMessage(selection.reason)
    }

    private fun readingPose(coordinates: Vec3): Pose {
        val pose = frontPose(coordinates.x, PullPolicy.DISTANCE_M + coordinates.z)
        pose.t.y += coordinates.y
        return pose
    }

    private fun anythingGrabbed(): Boolean {
        if (windows.values.any { it.entity.tryGetComponent<Grabbable>()?.isGrabbed == true }) return true
        return thoughtViews.values.any { it.entity.tryGetComponent<Grabbable>()?.isGrabbed == true }
    }

    private fun buildThoughtCard(slot: Int, context: Context): View {
        val open = thoughtViews[slot]
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ShellStyle.dp(context, 14), ShellStyle.dp(context, 10), ShellStyle.dp(context, 14), ShellStyle.dp(context, 10))
            background = ShellStyle.panel(context, "#121E2D", "#315266", 16)
        }
        val title = TextView(context).apply { textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) }
        val detail = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E7F0F8"))
            maxLines = 4
            setPadding(0, ShellStyle.dp(context, 4), 0, ShellStyle.dp(context, 4))
        }
        val steps = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val timerLine = LinearLayout(context)
        val timer = TextView(context).apply { textSize = 13f; setTextColor(Color.parseColor("#C8F5D4")) }
        fun cardAction(label: String, onClick: (String) -> Unit): Button {
            return Button(context).apply {
                text = label
                ShellStyle.button(context, this, label)
                textSize = 13f
                setOnClickListener {
                    val thoughtId = thoughtViews[slot]?.thoughtId ?: return@setOnClickListener
                    brain.selectedId = thoughtId
                    onClick(thoughtId)
                }
            }
        }
        val pause = cardAction(if (brain.desk.state == FocusRun.PAUSED) "Resume" else "Pause") { pauseOrResumeFocus() }
        timerLine.addView(timer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timerLine.addView(cardAction("Start") { startFocusTimer() }, LinearLayout.LayoutParams(0, ShellStyle.dp(context, 44), 0.7f))
        timerLine.addView(pause, LinearLayout.LayoutParams(0, ShellStyle.dp(context, 44), 0.7f))
        open?.root = column
        open?.titleView = title
        open?.detailView = detail
        open?.stepsHost = steps
        open?.timerView = timer
        open?.pauseButton = pause
        column.addView(title)
        column.addView(detail)
        column.addView(steps)
        column.addView(timerLine)
        fun actionRow(buttons: List<Button>) {
            val line = LinearLayout(context)
            val params = LinearLayout.LayoutParams(0, ShellStyle.dp(context, 46), 1f).apply {
                marginStart = ShellStyle.dp(context, 2); marginEnd = ShellStyle.dp(context, 2); topMargin = ShellStyle.dp(context, 4)
            }
            buttons.forEach { line.addView(it, params) }
            column.addView(line)
        }
        val focus = cardAction("Focus") { focusNow(it) }
        open?.focusButton = focus
        actionRow(listOf(
            cardAction("Pull here") { beginSling(it, RaySource.MENU, Vec3(0.18f, -0.35f, -0.55f)) },
            cardAction("Fan out") { fanOutNow(it) },
        ))
        actionRow(listOf(focus, cardAction("Toss") { tossThought(it) }))
        actionRow(listOf(
            cardAction("Arc") { arrangeThoughts("arc") },
            cardAction("Grid") { arrangeThoughts("grid") },
            cardAction("Stack") { arrangeThoughts("stack") },
        ))
        bindThoughtCard(slot)
        return column
    }

    private fun bindThoughtCard(slot: Int) {
        val open = thoughtViews[slot] ?: return
        val thought = brain.graph.find(open.thoughtId) ?: return
        open.titleView?.text = thought.title
        open.titleView?.setTextColor(Color.parseColor(when (thought.type) {
            ThoughtType.NOTE -> "#F3D27A"
            ThoughtType.TASK -> "#B7F0C8"
            else -> "#FFFFFF"
        }))
        val steps = TaskSteps.peek(thought.contentRef)
        open.detailView?.text = if (steps != null) TaskSteps.progress(steps) else thought.contentRef.ifBlank { thought.type.name.lowercase() }
        fillSteps(open, thought, steps)
        open.timerView?.text = FocusClock.label(brain.desk, System.currentTimeMillis())
        open.pauseButton?.text = if (brain.desk.state == FocusRun.PAUSED) "Resume" else "Pause"
        val focused = brain.focus?.rootId == open.thoughtId || stagingFocus?.rootId == open.thoughtId
        open.focusButton?.text = if (focused) "Exit focus" else "Focus"
        open.root?.alpha = cardAlpha(open.thoughtId)
    }

    private fun fillSteps(open: OpenThought, thought: Thought, steps: List<TaskItem>?) {
        val host = open.stepsHost ?: return
        host.removeAllViews()
        if (steps == null) return
        val context = host.context
        steps.forEach { item ->
            val row = LinearLayout(context)
            val mark = Button(context).apply {
                text = if (item.done) "Done" else "Check"
                textSize = 12f
                ShellStyle.button(context, this, text.toString())
                setOnClickListener {
                    brain.selectedId = thought.id
                    runCatching {
                        brain.graph.update(thought.id, brainProvenance("steps")) { current ->
                            current.copy(contentRef = TaskSteps.toggle(current.contentRef, item.id))
                        }
                    }.onSuccess {
                        saveBrain()
                        bindThoughtCard(open.slot)
                        setMessage(if (item.done) "Reopened ${item.text}." else "Checked ${item.text}.")
                    }.onFailure { setMessage(it.message ?: "That step was not changed.") }
                }
            }
            val label = TextView(context).apply {
                text = item.text
                textSize = 14f
                setTextColor(Color.parseColor(if (item.done) "#7E93A8" else "#E7F0F8"))
                setPadding(ShellStyle.dp(context, 8), 0, 0, 0)
            }
            row.addView(mark, LinearLayout.LayoutParams(ShellStyle.dp(context, 92), ShellStyle.dp(context, 40)))
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            host.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ShellStyle.dp(context, 4) })
        }
    }

    private fun placeThought(open: OpenThought) {
        if (motion.controls(open.thoughtId)) return
        val thought = brain.graph.find(open.thoughtId) ?: return
        val parent = room?.entity
        val roomId = room?.anchor?.uuid?.toString()
        val anchor = roomId?.let { brain.graph.anchorFor(thought.id, it) }
        if (parent != null) open.entity.setComponent(TransformParent(parent))
        val pose = if (anchor != null) {
            Pose(Vector3(anchor.position.x, anchor.position.y, anchor.position.z), Quaternion(anchor.rotationWxyz[0], anchor.rotationWxyz[1], anchor.rotationWxyz[2], anchor.rotationWxyz[3]))
        } else roomLocal(readingPose(thought.coordinates))
        open.entity.setComponent(Transform(pose))
        open.entity.setComponent(Visible(true))
        open.entity.setComponent(Grabbable(enabled = true))
        open.entity.setComponent(Scale(Vector3(1f)))
        open.root?.alpha = 1f
    }

    private fun openThoughtCard(thoughtId: String): OpenThought? {
        if (brain.graph.find(thoughtId) == null) return null
        thoughtViews.values.firstOrNull { it.thoughtId == thoughtId }?.let {
            placeThought(it); bindThoughtCard(it.slot); brain.selectedId = thoughtId; refreshThoughtStatus(); return it
        }
        val slot = (0 until ThoughtGraph.MAX_VISIBLE_CARDS).firstOrNull { it !in thoughtViews } ?: run {
            setMessage("Twelve cards are open. Collapse to free a slot. The library still holds every thought.")
            return null
        }
        val pose = Pose(Vector3(0f, 1.4f, 1f), Quaternion(1f, 0f, 0f, 0f))
        val entity = Entity.create(mutableListOf(Transform(pose), Grabbable(), Visible(true)))
        val open = OpenThought(thoughtId, slot, entity)
        thoughtViews[slot] = open
        entity.setComponent(Panel(THOUGHT_BASE + slot))
        placeThought(open)
        brain.selectedId = thoughtId
        refreshThoughtStatus()
        return open
    }

    private fun destroyThoughtCard(open: OpenThought) {
        thoughtViews.remove(open.slot)
        open.entity.destroy()
    }

    private fun relayoutThoughts() {
        abandonMotion()
        val ids = thoughtViews.values.map { it.thoughtId }
        thoughtViews.values.toList().forEach { destroyThoughtCard(it) }
        ids.filter { brain.graph.find(it) != null }.forEach { openThoughtCard(it) }
    }

    private fun newThought() {
        val labels = arrayOf("Note", "Reference", "Concept", "Task", "Model", "Conversation")
        val types = arrayOf(ThoughtType.NOTE, ThoughtType.REFERENCE, ThoughtType.CONCEPT, ThoughtType.TASK, ThoughtType.MODEL, ThoughtType.CONVERSATION)
        AlertDialog.Builder(this).setTitle("New thought").setItems(labels) { _, which ->
            val type = types[which]
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
            val title = EditText(this).apply { hint = "Title"; isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(60)) }
            val body = EditText(this).apply { hint = if (type == ThoughtType.REFERENCE) "https://example.com" else "Note"; isSingleLine = true }
            column.addView(title); column.addView(body)
            AlertDialog.Builder(this).setTitle(labels[which]).setView(column).setPositiveButton("Save") { _, _ ->
                runCatching {
                    brain.graph.create(type, title.text.toString(), body.text.toString().trim(), ThoughtPrivacy.PRIVATE, Vec3(0f, 0f, 0f), brainProvenance("create"))
                }.onSuccess { thought ->
                    brain.selectedId = thought.id; saveBrain(); openThoughtCard(thought.id); setMessage("Saved ${thought.title} in the library.")
                }.onFailure { setMessage(it.message ?: "Thought was not saved.") }
            }.setNegativeButton("Cancel", null).show()
        }.show()
    }

    private fun searchThoughts() {
        val field = EditText(this).apply { hint = "Title, note, or evidence"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Search thoughts").setView(field).setPositiveButton("Search") { _, _ ->
            val results = runCatching { brain.graph.search(field.text.toString()) }.getOrElse { setMessage(it.message ?: "Search failed."); return@setPositiveButton }
            if (results.isEmpty()) { setMessage("No matching thoughts."); return@setPositiveButton }
            val shown = results.take(30)
            AlertDialog.Builder(this).setTitle("${results.size} thoughts").setItems(shown.map { it.title }.toTypedArray()) { _, index ->
                brain.selectedId = shown[index].id; openThoughtCard(shown[index].id)
            }.show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun slingSelected() {
        val thought = selectedThought() ?: return
        beginSling(thought.id, RaySource.MENU, Vec3(0.18f, -0.35f, -0.55f))
    }

    private fun beginSling(thoughtId: String, source: RaySource, origin: Vec3) {
        if (!beginAllowed()) return
        val thought = brain.graph.find(thoughtId) ?: return
        val open = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId } ?: openThoughtCard(thoughtId) ?: return
        val preview = previewGesture(GestureKind.WEB_SLING, source, thoughtId)
        if (!preview.ok) { setMessage(preview.reason); return }
        brain.selectedId = thoughtId
        motion.beginSling(thoughtId, shownLibrary(open), OpeningScene.PULL_HOME, origin)
        arm(motion.frame())
        setMessage("Tether on ${thought.title}. Commit keeps it. Cancel springs it back.")
        if (source == RaySource.MENU) confirmMotion(
            "Pull ${thought.title} here?",
            "The tether draws it to reading distance. Commit keeps it there. Cancel springs it back. Scrolling or grabbing cancels it.",
        )
    }

    private fun commitSling(thoughtId: String) {
        motion.stop()
        motionClock = 0L
        hideProps()
        val result = brain.gestures.commit(menuScrolling(), anythingGrabbed())
        val open = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId }
        if (!result.committed) {
            if (open != null) placeThought(open)
            setMessage(result.reason)
            refreshThoughtStatus()
            return
        }
        val roomId = room?.anchor?.uuid?.toString()
        runCatching {
            if (roomId != null && brain.graph.anchorFor(thoughtId, roomId) != null) {
                val pose = roomLocal(frontPose(0f, PullPolicy.DISTANCE_M))
                brain.graph.anchor(thoughtId, roomId, Vec3(pose.t.x, pose.t.y, pose.t.z), listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z), brainProvenance("sling"))
            } else brain.graph.move(thoughtId, OpeningScene.PULL_HOME, brainProvenance("sling"))
        }.onSuccess {
            saveBrain()
            if (open != null) placeThought(open)
            setMessage("Committed the pull. Undo returns the thought.")
        }.onFailure {
            if (open != null) placeThought(open)
            setMessage(it.message ?: "The pull was not saved.")
        }
        refreshThoughtStatus()
    }

    private fun newCluster() {
        editName("Cluster", "Cluster name") { name ->
            val anchor = brain.selectedId?.let { brain.graph.find(it)?.coordinates } ?: Vec3(0.8f, 0f, 0.4f)
            runCatching { brain.graph.createCluster(name, anchor, brainProvenance("cluster")) }.onSuccess {
                saveBrain(); setMessage("Cluster $name is ready.")
            }.onFailure { setMessage(it.message ?: "Cluster was not created.") }
        }
    }

    private fun tossSelected() {
        val thought = selectedThought() ?: return
        val clusters = brain.graph.clusters()
        if (clusters.isEmpty()) { setMessage("Create a named cluster before tossing a thought."); return }
        if (!beginAllowed()) return
        AlertDialog.Builder(this).setTitle("Toss ${thought.title}").setItems(clusters.map { it.name }.toTypedArray()) { _, index ->
            if (!beginAllowed()) return@setItems
            val preview = runCatching { brain.graph.tossOnto(thought.id, clusters[index].id) }.getOrElse { setMessage(it.message ?: "No cluster."); return@setItems }
            beginToss(thought.id, preview, RaySource.MENU, withDialog = true)
        }.show()
    }

    private fun beginToss(thoughtId: String, preview: TossPreview, source: RaySource, withDialog: Boolean) {
        if (!beginAllowed()) return
        val thought = brain.graph.find(thoughtId) ?: return
        val open = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId } ?: openThoughtCard(thoughtId) ?: return
        val gesture = previewGesture(GestureKind.TOSS, source, thoughtId)
        if (!gesture.ok) { setMessage(gesture.reason); return }
        brain.selectedId = thoughtId
        tossInFlight = preview
        motion.beginToss(thoughtId, shownLibrary(open), preview.landing)
        arm(motion.frame())
        setMessage("Tossing ${thought.title} onto ${preview.clusterName}. Nothing is deleted.")
        if (withDialog) confirmMotion(
            "Toss onto ${preview.clusterName}?",
            "It arcs onto that cluster and stays in the library. Commit keeps the landing. Cancel flies it back. Nothing is deleted.",
        )
    }

    private fun commitTossLanding() {
        val preview = tossInFlight ?: return
        motion.stop()
        motionClock = 0L
        hideProps()
        val committed = brain.gestures.commit(menuScrolling(), anythingGrabbed())
        if (!committed.committed) {
            tossInFlight = null
            placeOpen(preview.thoughtId)
            setMessage(committed.reason)
            return
        }
        runCatching { brain.graph.commitToss(preview, brainProvenance("toss")) }.onSuccess {
            tossInFlight = null
            saveBrain()
            placeOpen(preview.thoughtId)
            setMessage("Tossed onto ${preview.clusterName}. Undo puts it back.")
        }.onFailure {
            tossInFlight = null
            if (brain.gestures.phase != GesturePhase.IDLE) brain.gestures.cancel()
            placeOpen(preview.thoughtId)
            setMessage(it.message ?: "Toss did not finish.")
        }
        refreshThoughtStatus()
    }

    private fun fanOut() {
        val thought = selectedThought() ?: return
        fanOutNow(thought.id)
    }

    private fun fanOutNow(thoughtId: String) {
        if (!beginAllowed()) return
        val thought = brain.graph.find(thoughtId) ?: return
        val layout = runCatching { brain.graph.layoutConstellation(thoughtId) }.getOrElse {
            setMessage(it.message ?: "Constellation was not opened."); return
        }
        val root = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId } ?: openThoughtCard(thoughtId) ?: return
        val rootAt = shownLibrary(root)
        val openAt = mutableMapOf<String, Vec3>()
        layout.nodes.forEach { node ->
            val existing = thoughtViews.values.firstOrNull { it.thoughtId == node.thought.id }
            if (existing != null) openAt[node.thought.id] = shownLibrary(existing)
            else if (openThoughtCard(node.thought.id) != null) {
                openedForMotion += node.thought.id
                openAt[node.thought.id] = rootAt
                thoughtViews.values.firstOrNull { it.thoughtId == node.thought.id }?.let { applyLibrary(it.entity, rootAt, 1f, grabbable = false, visible = true) }
            }
        }
        val tracks = ThoughtPaths.expand(thoughtId, rootAt, layout.nodes.map { it.thought.id to it.position }, openAt)
        brain.selectedId = thoughtId
        motion.beginTravel(MotionKind.EXPAND, tracks, thoughtId)
        onArrived = { finishExpand(thoughtId) }
        arm(motion.frame())
        setMessage("Opening ${thought.title}.")
    }

    private fun finishExpand(thoughtId: String) {
        openedForMotion.clear()
        val committed = runCatching { brain.graph.commitConstellation(thoughtId, brainProvenance("constellation")) }.getOrElse {
            thoughtViews.values.forEach { placeThought(it) }
            setMessage(it.message ?: "Constellation was not opened.")
            return
        }
        saveBrain()
        committed.nodes.forEach { node -> placeOpen(node.thought.id) }
        val title = brain.graph.find(thoughtId)?.title ?: "The idea"
        val extra = if (committed.omitted > 0) " ${committed.omitted} more stay in the library." else ""
        setMessage("Fanned $title into ${committed.nodes.size} thoughts.$extra")
        refreshThoughtStatus()
    }

    private fun pullThoughtHere(thoughtId: String) {
        if (motion.active) { setMessage("Finish or cancel the motion in the scene."); return }
        if (brain.focus != null) { setMessage("Exit focus before pulling a thought."); return }
        val thought = brain.graph.find(thoughtId) ?: return
        val open = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId } ?: openThoughtCard(thoughtId) ?: return
        val grabbed = open.entity.tryGetComponent<Grabbable>()?.isGrabbed == true
        val decision = PullPolicy.decide(grabbed)
        if (!decision.moved) { setMessage(decision.reason); return }
        runCatching { brain.graph.move(thoughtId, OpeningScene.PULL_HOME, brainProvenance("pull")) }.onSuccess {
            placeThought(open)
            brain.selectedId = thoughtId
            saveBrain()
            setMessage("Pulled ${thought.title} to reading distance.")
        }.onFailure { setMessage(it.message ?: "The thought was not pulled.") }
        refreshThoughtStatus()
    }

    private fun collapseSelected() {
        if (brain.focus != null || motion.kind == MotionKind.FOCUS) { setMessage("Exit focus before collapsing."); return }
        if (!beginAllowed()) return
        val thought = selectedThought() ?: return
        val open = thoughtViews.values.filter { brain.graph.find(it.thoughtId) != null }
        if (open.none { it.thoughtId == thought.id }) openThoughtCard(thought.id)
        val cards = thoughtViews.values.mapNotNull { card -> brain.graph.find(card.thoughtId)?.let { card } }
        val root = cards.firstOrNull { it.thoughtId == thought.id } ?: run {
            setMessage("Open that thought before collapsing.")
            return
        }
        if (cards.size <= 1) {
            setMessage("Collapsed to one card. Linked thoughts remain in the library.")
            return
        }
        val rootAt = shownLibrary(root)
        val tracks = ThoughtPaths.collapse(thought.id, rootAt, cards.map { it.thoughtId to shownLibrary(it) })
        motion.beginTravel(MotionKind.COLLAPSE, tracks, thought.id)
        onArrived = { finishCollapse(thought.id) }
        arm(motion.frame())
        setMessage("Collapsing to ${thought.title}.")
    }

    private fun finishCollapse(rootId: String) {
        thoughtViews.values.filter { it.thoughtId != rootId }.toList().forEach { destroyThoughtCard(it) }
        placeOpen(rootId)
        setMessage("Collapsed to one card. Linked thoughts remain in the library.")
        refreshThoughtStatus()
    }

    private fun enterFocus() {
        val thought = selectedThought() ?: return
        focusNow(thought.id)
    }

    private fun focusNow(thoughtId: String) {
        val thought = brain.graph.find(thoughtId) ?: return
        if (motion.kind == MotionKind.FOCUS) { cancelSceneMotion(); return }
        if (motion.kind == MotionKind.RESTORE) return
        if (brain.focus?.rootId == thoughtId) { exitFocus(); return }
        if (brain.focus != null) { exitFocus(); return }
        if (motion.active) { setMessage("Finish or cancel the motion in the scene."); return }
        val root = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId } ?: openThoughtCard(thoughtId) ?: return
        if (root.entity.tryGetComponent<Grabbable>()?.isGrabbed == true) { setMessage("Release the thought before focusing."); return }
        val layout = runCatching { brain.graph.layoutConstellation(thoughtId) }.getOrElse { setMessage(it.message ?: "Focus could not gather the related thoughts."); return }
        layout.nodes.forEach { node ->
            if (thoughtViews.values.none { it.thoughtId == node.thought.id } && openThoughtCard(node.thought.id) != null) openedForMotion += node.thought.id
        }
        brain.selectedId = thoughtId
        val cards = thoughtViews.values.associate { it.thoughtId to true }
        val windowVisibility = windows.values.associate { it.saved.id to !it.saved.hidden }
        val bubble = brain.graph.planFocus(thoughtId, cards, windowVisibility)
        stagingFocus = bubble
        val roomId = room?.anchor?.uuid?.toString()
        val homes = thoughtViews.values.mapNotNull { open -> brain.graph.find(open.thoughtId)?.let { open.thoughtId to shownLibrary(open) } }
        val anchored = thoughtViews.values.mapNotNull { open ->
            if (roomId != null && brain.graph.anchorFor(open.thoughtId, roomId) != null) open.thoughtId else null
        }.toSet()
        val tracks = ThoughtPaths.focus(homes, thoughtId, bubble.visibleThoughtIds, anchored)
        focusTracks.clear()
        focusTracks += tracks
        windowHomes.clear()
        windows.values.filter { !it.saved.hidden }.forEach { windowHomes[it.saved.id] = it.entity.getComponent<Transform>().transform }
        windowDimFrom = 0f
        windowDimTo = 1f
        motion.beginTravel(MotionKind.FOCUS, tracks, thoughtId)
        onArrived = {
            openedForMotion.clear()
            brain.focus = stagingFocus
            stagingFocus = null
            thoughtViews.values.forEach { bindThoughtCard(it.slot) }
            refreshThoughtStatus()
            setMessage("Focused on ${thought.title}. Exit focus brings everything back.")
        }
        arm(motion.frame())
        thoughtViews.values.forEach { bindThoughtCard(it.slot) }
        setMessage("Gathering ${thought.title}.")
    }

    private fun exitFocus() {
        if (motion.kind == MotionKind.FOCUS) { cancelSceneMotion(); return }
        if (motion.kind == MotionKind.RESTORE) return
        if (brain.focus == null) { setMessage("Focus is already off."); return }
        if (motion.active) { setMessage("Finish or cancel the motion in the scene."); return }
        if (focusTracks.isEmpty()) {
            snapClearFocus()
            refreshThoughtStatus()
            setMessage("Focus cleared. Everything is where you left it.")
            return
        }
        windowDimFrom = 1f
        windowDimTo = 0f
        motion.beginTravel(MotionKind.RESTORE, ThoughtPaths.restore(focusTracks), brain.focus?.rootId ?: focusTracks.first().id)
        onArrived = {
            focusTracks.clear()
            snapClearFocus()
            refreshThoughtStatus()
            setMessage("Focus cleared. Everything is where you left it.")
        }
        arm(motion.frame())
        setMessage("Bringing everything back.")
    }

    private fun pinSelected() {
        val thought = selectedThought() ?: return
        val roomId = room?.anchor?.uuid?.toString() ?: run { setMessage("Recognize a room before anchoring a thought."); return }
        val open = thoughtViews.values.firstOrNull { it.thoughtId == thought.id } ?: openThoughtCard(thought.id) ?: return
        val pose = open.entity.getComponent<Transform>().transform
        runCatching {
            brain.graph.anchor(thought.id, roomId, Vec3(pose.t.x, pose.t.y, pose.t.z), listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z), brainProvenance("anchor"))
        }.onSuccess {
            saveBrain(); placeThought(open); setMessage("Anchored ${thought.title} in $roomName. It returns when this room is recognized.")
        }.onFailure { setMessage(it.message ?: "Could not anchor the thought.") }
    }

    private fun showPalace() {
        val roomId = room?.anchor?.uuid?.toString() ?: run { setMessage("Recognize a room to recall anchored thoughts."); return }
        val anchors = runCatching { brain.graph.palace(roomId, roomId) }.getOrElse { setMessage(it.message ?: "Palace could not be read."); return }
        if (anchors.isEmpty()) { setMessage("No thoughts are anchored in $roomName."); return }
        var opened = 0
        anchors.forEach { if (openThoughtCard(it.thoughtId) != null) opened += 1 }
        val held = anchors.size - opened
        setMessage(if (held > 0) "Showing $opened anchored thoughts. $held more remain saved in $roomName." else "Showing $opened thoughts anchored in $roomName.")
    }

    private fun undoThought() {
        abandonMotion()
        if (brain.graph.undo() == null) { setMessage("Nothing to undo."); return }
        saveBrain(); relayoutThoughts(); refreshThoughtStatus(); setMessage("Undid the last change.")
    }

    private fun recallThoughts() {
        if (motion.active || brain.gestures.phase != GesturePhase.IDLE) { setMessage("Finish or cancel the motion in the scene."); return }
        if (brain.focus != null) { setMessage("Exit focus before recalling thoughts."); return }
        val open = thoughtViews.values.sortedBy { it.slot }
        if (open.isEmpty()) { setMessage("No thought cards are open."); return }
        val roomId = room?.anchor?.uuid?.toString()
        val pinned = mutableListOf<OpenThought>()
        val grabbed = mutableListOf<OpenThought>()
        val movable = mutableListOf<OpenThought>()
        open.forEach { card ->
            val held = card.entity.tryGetComponent<Grabbable>()?.isGrabbed == true
            val onWall = roomId != null && brain.graph.anchorFor(card.thoughtId, roomId) != null
            when {
                held -> grabbed += card
                onWall -> pinned += card
                else -> movable += card
            }
        }
        if (movable.isEmpty()) {
            setMessage(when {
                grabbed.isNotEmpty() -> "Release a thought before recalling it."
                else -> "Pinned thoughts stay on the wall."
            })
            return
        }
        val seats = ReadingArc.seats(movable.size)
        var moved = 0
        movable.forEachIndexed { index, card ->
            runCatching { brain.graph.move(card.thoughtId, seats[index], brainProvenance("recall")) }.onSuccess {
                placeThought(card)
                moved += 1
            }
        }
        if (moved == 0) { setMessage("Thoughts could not be recalled."); return }
        saveBrain()
        val heldBack = grabbed.size + pinned.size
        setMessage(if (heldBack == 0) "Recalled $moved thoughts to reading distance."
            else "Recalled $moved thoughts to reading distance. $heldBack stayed put.")
    }

    private fun showDesk() {
        syncDesk(false)
        val pauseLabel = if (brain.desk.state == FocusRun.PAUSED) "Resume timer" else "Pause timer"
        val labels = arrayOf("Arc", "Grid", "Stack", "Start focus", pauseLabel, "Skip phase", "Reset timer", "Steps")
        AlertDialog.Builder(this).setTitle("Desk")
            .setMessage("Arrange the open thoughts, run a focus timer, or check steps on a task. This stays on the headset.")
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> arrangeThoughts("arc")
                    1 -> arrangeThoughts("grid")
                    2 -> arrangeThoughts("stack")
                    3 -> startFocusTimer()
                    4 -> pauseOrResumeFocus()
                    5 -> skipFocus()
                    6 -> resetFocus()
                    else -> editSteps()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun tossThought(thoughtId: String) {
        brain.selectedId = thoughtId
        val clusters = brain.graph.clusters()
        if (clusters.isEmpty()) { setMessage("Create a named cluster before tossing a thought."); return }
        if (!beginAllowed()) return
        if (clusters.size == 1) {
            val preview = runCatching { brain.graph.tossOnto(thoughtId, clusters.first().id) }.getOrElse {
                setMessage(it.message ?: "No cluster."); return
            }
            beginToss(thoughtId, preview, RaySource.MENU, withDialog = true)
            return
        }
        val thought = brain.graph.find(thoughtId) ?: return
        AlertDialog.Builder(this).setTitle("Toss ${thought.title}").setItems(clusters.map { it.name }.toTypedArray()) { _, index ->
            if (!beginAllowed()) return@setItems
            val preview = runCatching { brain.graph.tossOnto(thoughtId, clusters[index].id) }.getOrElse {
                setMessage(it.message ?: "No cluster."); return@setItems
            }
            beginToss(thoughtId, preview, RaySource.MENU, withDialog = true)
        }.show()
    }

    private fun arrangeThoughts(arrangement: String) {
        if (brain.gestures.phase != GesturePhase.IDLE) { setMessage("Finish or cancel the current gesture."); return }
        if (brain.focus != null) { setMessage("Exit focus before arranging thoughts."); return }
        val roomId = room?.anchor?.uuid?.toString()
        val movable = thoughtViews.values.sortedBy { it.slot }.filter { card ->
            card.entity.tryGetComponent<Grabbable>()?.isGrabbed != true &&
                (roomId == null || brain.graph.anchorFor(card.thoughtId, roomId) == null)
        }
        if (movable.size < 2) { setMessage("Open at least two thoughts that are not pinned or held."); return }
        val spots = runCatching { HoloArranger.readingSpots(arrangement, movable.size) }.getOrElse {
            setMessage(it.message ?: "Those thoughts were not arranged."); return
        }
        var moved = 0
        movable.forEachIndexed { index, card ->
            runCatching { brain.graph.move(card.thoughtId, spots[index], brainProvenance(arrangement)) }.onSuccess {
                placeThought(card)
                moved += 1
            }
        }
        if (moved == 0) { setMessage("Those thoughts were not arranged."); return }
        saveBrain()
        val shape = when (arrangement) { "grid" -> "a grid"; "stack" -> "a stack"; else -> "an arc" }
        val stayed = thoughtViews.size - movable.size
        val extra = if (stayed > 0) " Pinned and held cards stayed put." else ""
        setMessage("Arranged $moved thoughts in $shape.$extra")
    }

    private fun startFocusTimer() {
        syncDesk(false)
        if (brain.desk.state == FocusRun.RUNNING && brain.desk.phase == FocusPhase.FOCUS) {
            setMessage("Focus is already running."); return
        }
        val task = brain.selectedId?.let { brain.graph.find(it)?.title }.orEmpty()
        brain.desk = FocusClock.start(brain.desk, System.currentTimeMillis(), task)
        saveDesk()
        syncDesk(false)
        setMessage("Focus started. ${FocusClock.label(brain.desk, System.currentTimeMillis())}.")
    }

    private fun pauseOrResumeFocus() {
        syncDesk(false)
        val now = System.currentTimeMillis()
        brain.desk = when (brain.desk.state) {
            FocusRun.RUNNING -> runCatching { FocusClock.pause(brain.desk, now) }.getOrElse {
                setMessage(it.message ?: "The timer is not running."); return
            }
            FocusRun.PAUSED -> runCatching { FocusClock.resume(brain.desk, now) }.getOrElse {
                setMessage(it.message ?: "The timer is not paused."); return
            }
            FocusRun.IDLE -> { setMessage("Start a focus timer first."); return }
        }
        saveDesk()
        syncDesk(false)
        setMessage(if (brain.desk.state == FocusRun.PAUSED) "Timer paused." else "Timer resumed.")
    }

    private fun skipFocus() {
        syncDesk(false)
        val next = runCatching { FocusClock.skip(brain.desk, System.currentTimeMillis()) }.getOrElse {
            setMessage(it.message ?: "Start a focus timer first."); return
        }
        brain.desk = next
        saveDesk()
        syncDesk(false)
        setMessage(FocusClock.advancedMessage(brain.desk))
    }

    private fun resetFocus() {
        val kept = brain.desk.completedSessions
        brain.desk = FocusClock.reset(brain.desk)
        saveDesk()
        syncDesk(false)
        setMessage(if (kept == 0) "Timer reset." else "Timer reset. $kept completed sessions stay counted.")
    }

    private fun editSteps() {
        val thought = selectedThought() ?: return
        if (thought.type != ThoughtType.TASK) { setMessage("Steps belong on a task."); return }
        val existing = runCatching { TaskSteps.read(thought.contentRef) }.getOrElse {
            setMessage(it.message ?: "Those steps could not be read."); return
        }
        if (existing == null) {
            AlertDialog.Builder(this).setTitle(thought.title)
                .setMessage("Turn this note into a checklist? The sentence becomes the first step.")
                .setPositiveButton("Make steps") { _, _ ->
                    val text = thought.contentRef.ifBlank { thought.title }.take(80)
                    runCatching {
                        brain.graph.update(thought.id, brainProvenance("steps")) {
                            it.copy(contentRef = TaskSteps.write(listOf(TaskItem("step", text, false))))
                        }
                    }.onSuccess {
                        saveBrain(); bindOpen(thought.id); setMessage("Added a step to ${thought.title}.")
                    }.onFailure { setMessage(it.message ?: "Steps were not saved.") }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        showStepList(thought.id, existing)
    }

    private fun showStepList(thoughtId: String, items: List<TaskItem>) {
        val thought = brain.graph.find(thoughtId) ?: return
        val labels = items.map { (if (it.done) "Done · " else "Open · ") + it.text }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Steps · ${thought.title}").setItems(labels) { _, index ->
            val item = items[index]
            runCatching {
                brain.graph.update(thoughtId, brainProvenance("steps")) { current ->
                    current.copy(contentRef = TaskSteps.toggle(current.contentRef, item.id))
                }
            }.onSuccess { updated ->
                saveBrain()
                bindOpen(thoughtId)
                setMessage(if (item.done) "Reopened ${item.text}." else "Checked ${item.text}.")
                val next = TaskSteps.peek(updated.contentRef)
                if (next != null) placementHandler.post { showStepList(thoughtId, next) }
            }.onFailure { setMessage(it.message ?: "That step was not changed.") }
        }.setNeutralButton("Add") { _, _ -> addStep(thoughtId) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addStep(thoughtId: String) {
        val field = EditText(this).apply { hint = "Step"; isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        AlertDialog.Builder(this).setTitle("Add a step").setView(field).setPositiveButton("Add") { _, _ ->
            val text = field.text.toString().trim()
            if (text.isEmpty()) { setMessage("A step needs a few words."); return@setPositiveButton }
            val current = brain.graph.find(thoughtId) ?: return@setPositiveButton
            val items = TaskSteps.peek(current.contentRef) ?: run { setMessage("This task has no steps."); return@setPositiveButton }
            val id = (1..99).firstNotNullOfOrNull { n -> "s$n".takeIf { candidate -> items.none { it.id == candidate } } }
                ?: run { setMessage("This checklist is full."); return@setPositiveButton }
            runCatching {
                brain.graph.update(thoughtId, brainProvenance("steps")) { thought ->
                    thought.copy(contentRef = TaskSteps.add(thought.contentRef, id, text))
                }
            }.onSuccess { updated ->
                saveBrain(); bindOpen(thoughtId); setMessage("Added a step.")
                val next = TaskSteps.peek(updated.contentRef)
                if (next != null) placementHandler.post { showStepList(thoughtId, next) }
            }.onFailure { setMessage(it.message ?: "That step was not added.") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun bindOpen(thoughtId: String) {
        thoughtViews.values.filter { it.thoughtId == thoughtId }.forEach { bindThoughtCard(it.slot) }
    }

    private fun showHistory() {
        val selected = brain.selectedId?.let { brain.graph.find(it) }
        if (selected != null) {
            val steps = brain.graph.ideaHistory(selected.id)
            if (steps.isEmpty()) { setMessage("No history for ${selected.title}."); return }
            val labels = steps.map { step ->
                val name = if (step.present) step.title else "(removed)"
                "${step.eventCount}. ${step.kind} · $name"
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("History of ${selected.title}").setItems(labels) { _, index ->
                val step = steps[index]
                val now = brain.graph.find(selected.id)
                val then = if (step.present) "${step.title}: ${step.contentRef}" else "not in the library"
                val current = if (now == null) "not in the library" else "${now.title}: ${now.contentRef}"
                setMessage("After event ${step.eventCount}, it was $then. Now it is $current. History was not changed.")
            }.show()
            return
        }
        val events = brain.graph.events()
        if (events.isEmpty()) { setMessage("No thought history yet."); return }
        val window = events.takeLast(20)
        val start = events.size - window.size
        val labels = window.mapIndexed { index, event ->
            val title = brain.graph.find(event.subjectId)?.title ?: "item"
            "${start + index + 1}. ${event.kind} · $title · ${event.provenance.source}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Thought history").setItems(labels) { _, index ->
            val count = start + index + 1
            val past = brain.graph.stateAt(count)
            val then = past.thoughts.firstOrNull { it.id == window[index].subjectId }?.title ?: "(not in the library at this point)"
            setMessage("After event $count: $then. ${past.thoughts.size} thoughts stored. History was not changed.")
        }.show()
    }

    private fun linkSelected() {
        val thought = selectedThought() ?: return
        val others = brain.graph.thoughts().filter { it.id != thought.id }
        if (others.isEmpty()) { setMessage("Create another thought before linking."); return }
        val relations = arrayOf("related", "evidence", "supports")
        AlertDialog.Builder(this).setTitle("Link type").setItems(relations) { _, relationIndex ->
            val relation = relations[relationIndex]
            AlertDialog.Builder(this).setTitle("Link ${thought.title}").setItems(others.map { it.title }.toTypedArray()) { _, index ->
                if (relation == "evidence") {
                    val field = EditText(this).apply { hint = "Evidence reference"; isSingleLine = true }
                    AlertDialog.Builder(this).setTitle("Evidence").setView(field).setPositiveButton("Link") { _, _ ->
                        saveLink(thought.id, others[index].id, relation, field.text.toString())
                    }.setNegativeButton("Cancel", null).show()
                } else saveLink(thought.id, others[index].id, relation, "")
            }.show()
        }.show()
    }

    private fun saveLink(fromId: String, toId: String, relation: String, evidence: String) {
        runCatching { brain.graph.link(fromId, toId, relation, evidence, brainProvenance("link")) }.onSuccess {
            saveBrain(); setMessage("Link saved.")
        }.onFailure { setMessage(it.message ?: "Link was not saved.") }
    }

    private fun editSelected() {
        val thought = selectedThought() ?: return
        AlertDialog.Builder(this).setTitle(thought.title).setItems(arrayOf("Rename", "Edit note", "Privacy", "Delete")) { _, which ->
            when (which) {
                0 -> editName(thought.title, "Thought name") { value ->
                    runCatching { brain.graph.update(thought.id, brainProvenance("rename")) { it.copy(title = value) } }.onSuccess {
                        saveBrain(); thoughtViews.values.filter { it.thoughtId == thought.id }.forEach { bindThoughtCard(it.slot) }; refreshThoughtStatus()
                    }.onFailure { setMessage(it.message ?: "Could not rename.") }
                }
                1 -> {
                    val field = EditText(this).apply { setText(thought.contentRef); isSingleLine = true }
                    AlertDialog.Builder(this).setTitle("Note").setView(field).setPositiveButton("Save") { _, _ ->
                        runCatching { brain.graph.update(thought.id, brainProvenance("edit")) { it.copy(contentRef = field.text.toString().trim()) } }.onSuccess {
                            saveBrain(); setMessage("Note updated.")
                        }.onFailure { setMessage(it.message ?: "Note was not saved.") }
                    }.setNegativeButton("Cancel", null).show()
                }
                2 -> cyclePrivacy(thought)
                3 -> confirmDeleteThought(thought)
            }
        }.show()
    }

    private fun cyclePrivacy(thought: Thought) {
        if (thought.type == ThoughtType.CONVERSATION) { setMessage("Conversations stay private and are never shared."); return }
        val next = when (thought.privacy) {
            ThoughtPrivacy.PRIVATE -> ThoughtPrivacy.ROOM
            ThoughtPrivacy.ROOM -> ThoughtPrivacy.SHAREABLE
            ThoughtPrivacy.SHAREABLE -> ThoughtPrivacy.PRIVATE
        }
        val warning = if (next == ThoughtPrivacy.SHAREABLE) "This thought can be approved for co-think. It is not shared yet." else "Privacy is ${next.name.lowercase()}."
        AlertDialog.Builder(this).setTitle("Change privacy?").setMessage(warning).setPositiveButton("Change") { _, _ ->
            runCatching { brain.graph.update(thought.id, brainProvenance("privacy")) { it.copy(privacy = next) } }.onSuccess {
                saveBrain(); thoughtViews.values.filter { it.thoughtId == thought.id }.forEach { bindThoughtCard(it.slot) }; refreshThoughtStatus(); setMessage(warning)
            }.onFailure { setMessage(it.message ?: "Privacy was not changed.") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeleteThought(thought: Thought) {
        AlertDialog.Builder(this).setTitle("Delete ${thought.title}?").setMessage("This removes it from the library. Undo can bring it back. Tossing never deletes.")
            .setPositiveButton("Delete") { _, _ ->
                runCatching { brain.graph.delete(thought.id, confirmed = true, brainProvenance("delete")) }.onSuccess {
                    if (brain.selectedId == thought.id) brain.selectedId = null
                    if (motion.active) abandonMotion()
                    if (brain.focus?.rootId == thought.id) snapClearFocus()
                    thoughtViews.values.filter { it.thoughtId == thought.id }.toList().forEach { destroyThoughtCard(it) }
                    saveBrain(); refreshThoughtStatus(); setMessage("Deleted ${thought.title}. Undo can restore it.")
                }.onFailure { setMessage(it.message ?: "Thought was not deleted.") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startCoThink() {
        AlertDialog.Builder(this).setTitle("Host a co-think session?")
            .setMessage("Nothing is shared until you approve specific thoughts. Conversations, camera snapshots, cookies, and room anchors cannot be approved. A guest headset joins this session and receives only what you approve. Two physical headsets have not been tested.")
            .setPositiveButton("Host") { _, _ ->
                runCatching { coThinkHost?.close() }
                coThinkHost = null
                val session = CoThinkSession(UUID.randomUUID().toString(), SessionRole.HOST)
                brain.share = session
                val host = runCatching { CoThinkHost(session.id, { brain.share }, { brain.graph }) }.getOrElse {
                    brain.share = null
                    setMessage(it.message ?: "The co-think session could not be hosted.")
                    return@setPositiveButton
                }
                coThinkHost = host
                refreshThoughtStatus()
                setMessage("Hosting on port ${host.port}. Session ${session.id}. Approve thoughts, then the other headset joins. Not tested on two physical headsets.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun approveShare() {
        val session = brain.share ?: run { setMessage("Host a co-think session first."); return }
        if (session.role != SessionRole.HOST || coThinkHost == null) { setMessage("Guests cannot approve a share."); return }
        val eligible = brain.graph.thoughts().filter { canShare(it) }
        if (eligible.isEmpty()) { setMessage("No shareable thoughts. Conversations stay private."); return }
        val checked = BooleanArray(eligible.size)
        AlertDialog.Builder(this).setTitle("Approve thoughts").setMultiChoiceItems(eligible.map { it.title }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Approve") { _, _ ->
                val ids = eligible.filterIndexed { index, _ -> checked[index] }.map { it.id }
                if (ids.isEmpty()) { setMessage("No thoughts approved. Nothing was shared."); return@setPositiveButton }
                runCatching { session.approve(brain.graph, ids) }
                    .onSuccess {
                        val port = coThinkHost?.port
                        setMessage("Approved ${ids.size} thoughts on port $port. A guest receives only these. Not tested on two physical headsets.")
                    }
                    .onFailure { setMessage(it.message ?: "Those thoughts cannot be shared.") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun joinCoThink() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val note = TextView(this).apply { text = "Only thoughts the host approved are imported. Conversations, cookies, camera, and room anchors stay behind. Not tested on two physical headsets." }
        val hostField = EditText(this).apply { hint = "Host address"; setText("127.0.0.1"); isSingleLine = true }
        val portField = EditText(this).apply { hint = "Port"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine = true }
        val sessionField = EditText(this).apply { hint = "Session id"; isSingleLine = true }
        column.addView(note); column.addView(hostField); column.addView(portField); column.addView(sessionField)
        AlertDialog.Builder(this).setTitle("Join as guest?").setView(column)
            .setPositiveButton("Join") { _, _ ->
                val address = hostField.text.toString().trim()
                val portNumber = portField.text.toString().trim().toIntOrNull()
                val sessionId = sessionField.text.toString().trim()
                if (portNumber == null) { setMessage("Enter the host port."); return@setPositiveButton }
                runCatching { coThinkHost?.close() }
                coThinkHost = null
                documentIo.execute {
                    val result = runCatching { pullEnvelope(address, portNumber, sessionId) }
                    runOnUiThread {
                        if (shuttingDown) return@runOnUiThread
                        result.onSuccess { envelope ->
                            brain.share = CoThinkSession(envelope.sessionId, SessionRole.GUEST)
                            runCatching { importEnvelope(brain.graph, envelope, Provenance("import", "co-think-guest", System.currentTimeMillis(), "guest import")) }
                                .onSuccess { saveBrain(); refreshThoughtStatus(); setMessage("Imported ${envelope.thoughts.size} approved thoughts. Nothing else was replaced.") }
                                .onFailure { setMessage(it.message ?: "The share was not imported.") }
                        }.onFailure { setMessage(it.message ?: "The other headset did not share.") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun retrieve() {
        val field = EditText(this).apply { hint = "What should I look up?"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Ask using local notes").setView(field).setPositiveButton("Review") { _, _ ->
            val query = field.text.toString().trim()
            val hits = runCatching { brain.graph.search(query) }.getOrElse { setMessage(it.message ?: "Search failed."); return@setPositiveButton }
            val prompt = runCatching { Retrieval.prompt(query, hits) }.getOrElse { setMessage(it.message ?: "Could not prepare a question."); return@setPositiveButton }
            routeAssistantDraft(prompt)
            setMessage("Local notes offered for review. They were not sent automatically.")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun lookAndAsk() {
        if (!brain.look.optedIn) {
            AlertDialog.Builder(this).setTitle("Look and ask is opt-in")
                .setMessage("Arming shows a capture indicator. A snapshot is kept only if you say so, and it is not sent until you submit a question. Luma cannot call ChatGPT tools.")
                .setPositiveButton("Opt in") { _, _ -> brain.look.optIn(); lookAndAsk() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        when (brain.look.phase) {
            CapturePhase.IDLE, CapturePhase.SUBMITTED -> { setMessage(brain.look.arm()); refreshThoughtStatus() }
            CapturePhase.ARMED -> {
                val thought = selectedThought() ?: return
                AlertDialog.Builder(this).setTitle("CAPTURE ON")
                    .setMessage("Keep ${thought.title} as the object? A short snapshot stays on this headset. The camera is not used, and the snapshot is not shared.")
                    .setPositiveButton("Keep") { _, _ ->
                        val bytes = "${thought.title}\n${thought.contentRef}".toByteArray(Charsets.UTF_8)
                        runCatching { brain.look.captureObject(thought.id, thought.title, bytes) }
                            .onSuccess { setMessage("Kept ${thought.title} on this headset. Submit a question or cancel."); refreshThoughtStatus() }
                            .onFailure { setMessage(it.message ?: "The object was not captured.") }
                    }.setNegativeButton("Cancel capture") { _, _ -> brain.look.cancel(); setMessage("Capture canceled."); refreshThoughtStatus() }.show()
            }
            CapturePhase.CAPTURED -> {
                val field = EditText(this).apply { hint = "What is this?"; isSingleLine = true }
                AlertDialog.Builder(this).setTitle("Question about the object").setView(field)
                    .setPositiveButton("Review") { _, _ ->
                        val request = runCatching { brain.look.submit(field.text.toString()) }.getOrElse { setMessage(it.message ?: "Question was not submitted."); return@setPositiveButton }
                        routeAssistantDraft("I am looking at ${request.objectLabel}. ${request.prompt}\n\nPrepared on device. This does not call ChatGPT tools. Send only if you want this question in the conversation.")
                        setMessage("Question offered to an assistant window. The snapshot stayed on this headset."); refreshThoughtStatus()
                    }.setNegativeButton("Cancel") { _, _ -> brain.look.cancel(); refreshThoughtStatus() }.show()
            }
        }
    }

    private fun buildBench() {
        val thought = selectedThought() ?: return
        val ids = (listOf(thought.id) + brain.graph.neighbors(thought.id).map { it.id }).distinct().take(3)
        val items = runCatching { BuildBench.materials(brain.graph, ids) }.getOrElse { setMessage(it.message ?: "Bench could not be prepared."); return }
        val summary = items.joinToString("\n") { item ->
            val title = brain.graph.requireThought(item.thoughtId).title
            when (item.kind) {
                "browser" -> "Browser: ${item.ref}"
                "widget" -> "Offline widget: $title"
                else -> "Stays in the assistant: $title"
            }
        }
        AlertDialog.Builder(this).setTitle("Build bench").setMessage(summary).setPositiveButton("Open") { _, _ ->
            items.forEach { openBenchItem(it) }; saveBrain()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openBenchItem(item: BenchItem) {
        if (item.kind == "assistant") { setMessage("Conversations stay in the assistant panel."); return }
        if (item.kind == "widget" && item.html.toByteArray(Charsets.UTF_8).size > RoomLayoutCodec.MAX_HTML_BYTES) { setMessage("Note is too large for a widget."); return }
        val before = windows.keys.toSet()
        when (item.kind) {
            "browser" -> addWindow("browser", requestedUrl = item.ref)
            "widget" -> addWindow("widget", widgetHtml = item.html)
        }
        val created = windows.values.firstOrNull { it.saved.slot !in before } ?: return
        runCatching { brain.graph.attachPanel(item.thoughtId, created.saved.id, brainProvenance("bench")) }
            .onFailure { setMessage(it.message ?: "The bench opened, but the thought was not linked to the panel.") }
    }

    private fun menuScrolling() = SystemClock.uptimeMillis() < scrollQuietUntil

    private fun beginAllowed(): Boolean {
        if (motion.active || brain.gestures.phase != GesturePhase.IDLE) {
            setMessage("Finish or cancel the motion in the scene.")
            return false
        }
        if (brain.focus != null) {
            setMessage("Exit focus before moving thoughts another way.")
            return false
        }
        return true
    }

    private fun previewGesture(kind: GestureKind, source: RaySource, targetId: String): GestureResult {
        val aimed = brain.gestures.aim(kind, source, targetId, menuScrolling(), anythingGrabbed())
        if (!aimed.ok) return aimed
        val selected = brain.gestures.select()
        if (!selected.ok) return selected
        return brain.gestures.preview()
    }

    private fun confirmMotion(title: String, message: String) {
        var open = true
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Commit") { _, _ -> open = false; requestCommit() }
            .setNegativeButton("Cancel") { _, _ -> if (open) cancelSceneMotion() }
            .setOnCancelListener { if (open) cancelSceneMotion() }
            .show()
    }

    private fun requestCommit() {
        if (!motion.active || motion.returning) return
        val action: () -> Unit = when (motion.kind) {
            MotionKind.SLING -> {
                val id = motion.subjectId ?: return
                { commitSling(id) }
            }
            MotionKind.TOSS -> {
                { commitTossLanding() }
            }
            else -> return
        }
        if (motion.arrived) action() else {
            commitWhenArrived = action
            setMessage("Watch it land. It stays when it gets there.")
        }
    }

    private fun arm(frame: MotionFrame?) {
        motionClock = 0L
        if (frame != null) showMotion(frame)
    }

    private fun stepSceneMotion() {
        if (!motion.active) return
        if (motion.kind == MotionKind.SLING && !motion.returning) {
            val interrupted = brain.gestures.interrupt(menuScrolling(), anythingGrabbed())
            if (brain.gestures.phase == GesturePhase.IDLE) {
                returnMessage = interrupted.reason
                cancelSceneMotion(announce = false)
                return
            }
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (motionClock == 0L) 0.016f else (now - motionClock).coerceIn(1L, 50L) / 1000f
        motionClock = now
        val frame = motion.step(dt) ?: return
        showMotion(frame)
        if (frame.arrived && frame.holding && commitWhenArrived != null) {
            val action = commitWhenArrived
            commitWhenArrived = null
            action?.invoke()
            return
        }
        if (frame.justArrived && frame.holding) holdMessage()
        if (frame.justArrived && !frame.holding) {
            val action = onArrived
            onArrived = null
            motion.stop()
            motionClock = 0L
            hideProps()
            action?.invoke()
        }
    }

    private fun holdMessage() {
        val title = motion.subjectId?.let { brain.graph.find(it)?.title } ?: "The thought"
        val text = when (motion.kind) {
            MotionKind.SLING -> "Tether on $title. Commit keeps it here. Cancel springs it back."
            MotionKind.TOSS -> "Tossed toward ${tossInFlight?.clusterName ?: "the cluster"}. Commit keeps the landing. Cancel flies it back."
            else -> return
        }
        setMessage(text)
    }

    private fun showMotion(frame: MotionFrame) {
        frame.cards.forEach { sample ->
            val open = thoughtViews.values.firstOrNull { it.thoughtId == sample.id } ?: return@forEach
            applyCard(open, sample)
        }
        if (windowHomes.isNotEmpty()) applyWindowDim(ThoughtPaths.mix(windowDimFrom, windowDimTo, frame.progress))
        if (frame.tether == null) tetherBeads.forEach { it.setComponent(Visible(false)) }
        else {
            ensureProps()
            frame.tether.beads.forEachIndexed { index, point ->
                val bead = tetherBeads.getOrNull(index) ?: return@forEachIndexed
                applyLibrary(bead, point, 1f, grabbable = false, visible = true)
            }
        }
        if (frame.landing == null) landingMarker?.setComponent(Visible(false))
        else {
            ensureProps()
            landingMarker?.let { applyLibrary(it, frame.landing, 1f, grabbable = false, visible = true) }
        }
    }

    private fun applyCard(open: OpenThought, sample: CardSample) {
        applyLibrary(open.entity, sample.position, sample.scale, grabbable = false, visible = true)
        open.root?.alpha = 1f - sample.dim.coerceIn(0f, 1f) * 0.78f
    }

    private fun cardAlpha(thoughtId: String): Float {
        motion.sampleOf(thoughtId)?.let { return 1f - it.dim * 0.78f }
        if (brain.focus != null) {
            val dim = focusTracks.firstOrNull { it.id == thoughtId }?.toDim ?: 0f
            return 1f - dim * 0.78f
        }
        return 1f
    }

    private fun applyLibrary(entity: Entity, coordinates: Vec3, scale: Float, grabbable: Boolean, visible: Boolean) {
        room?.entity?.let { entity.setComponent(TransformParent(it)) }
        entity.setComponent(Transform(roomLocal(readingPose(coordinates))))
        entity.setComponent(Scale(Vector3(scale)))
        entity.setComponent(Visible(visible))
        if (grabbable || entity.tryGetComponent<Grabbable>() != null) entity.setComponent(Grabbable(enabled = grabbable))
    }

    private fun applyWindowDim(dim: Float) {
        val bubble = stagingFocus ?: brain.focus ?: return
        windows.values.forEach { window ->
            if (window.saved.hidden) return@forEach
            val home = windowHomes[window.saved.id] ?: return@forEach
            val included = bubble.windowVisibility[window.saved.id] ?: false
            val amount = if (included) 0f else dim.coerceIn(0f, 1f)
            window.entity.setComponent(Transform(Pose(Vector3(home.t.x, home.t.y - 0.12f * amount, home.t.z), home.q)))
            window.entity.setComponent(Scale(Vector3(window.saved.size * (1f - 0.5f * amount))))
            window.entity.setComponent(Visible(true))
        }
    }

    private fun syncLinks() {
        if (!sceneReady || shuttingDown || propsFailed) return
        val open = thoughtViews.values.mapNotNull { card ->
            val at = motion.sampleOf(card.thoughtId)?.position ?: brain.graph.find(card.thoughtId)?.let { shownLibrary(card) }
            at?.let { card.thoughtId to it }
        }.toMap()
        val visible = brain.graph.links().filter { it.fromId in open && it.toId in open }.take(LINK_BARS)
        if (visible.isNotEmpty() && linkBars.isEmpty()) {
            runCatching {
                repeat(LINK_BARS) { linkBars += linkBar() }
            }.onFailure {
                linkBars.forEach { runCatching { it.destroy() } }
                linkBars.clear()
            }
        }
        linkBars.forEachIndexed { index, bar ->
            val link = visible.getOrNull(index)
            if (link == null) bar.setComponent(Visible(false))
            else placeBar(bar, open.getValue(link.fromId), open.getValue(link.toId))
        }
    }

    private fun linkBar(): Entity {
        val half = 0.5f
        val entity = Entity.create(listOf(
            Mesh(Uri.parse("mesh://box")),
            Box(Vector3(-half, -half, -half), Vector3(half, half, half)),
            Material().apply { baseColor = Color4(0.45f, 0.78f, 0.95f, 1f) },
            Transform(Pose(Vector3(0f, 1f, 1f), Quaternion(1f, 0f, 0f, 0f))),
            Visible(false),
        ))
        room?.entity?.let { entity.setComponent(TransformParent(it)) }
        return entity
    }

    private fun placeBar(bar: Entity, from: Vec3, to: Vec3) {
        val start = roomLocal(readingPose(from)).t
        val end = roomLocal(readingPose(to)).t
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.05f) { bar.setComponent(Visible(false)); return }
        room?.entity?.let { bar.setComponent(TransformParent(it)) }
        val mid = Vector3((start.x + end.x) / 2f, (start.y + end.y) / 2f, (start.z + end.z) / 2f)
        bar.setComponent(Transform(Pose(mid, Quaternion.lookRotation(Vector3(dx, dy, dz)))))
        bar.setComponent(Scale(Vector3(0.012f, 0.012f, len)))
        bar.setComponent(Visible(true))
    }

    private fun ensureProps() {
        if (propsFailed || tetherBeads.isNotEmpty()) return
        runCatching {
            repeat(ThoughtPaths.BEADS) { tetherBeads += sceneBead(0.016f, Color4(0.55f, 0.95f, 0.82f, 1f)) }
            landingMarker = sceneBead(0.04f, Color4(0.96f, 0.76f, 0.32f, 1f))
        }.onFailure {
            tetherBeads.forEach { runCatching { it.destroy() } }
            tetherBeads.clear()
            landingMarker = null
            propsFailed = true
        }
    }

    private fun sceneBead(half: Float, color: Color4): Entity {
        val entity = Entity.create(listOf(
            Mesh(Uri.parse("mesh://box")),
            Box(Vector3(-half, -half, -half), Vector3(half, half, half)),
            Material().apply { baseColor = color },
            Transform(Pose(Vector3(0f, 1f, 1f), Quaternion(1f, 0f, 0f, 0f))),
            Visible(false),
        ))
        room?.entity?.let { entity.setComponent(TransformParent(it)) }
        return entity
    }

    private fun hideProps() {
        tetherBeads.forEach { runCatching { it.setComponent(Visible(false)) } }
        landingMarker?.let { runCatching { it.setComponent(Visible(false)) } }
    }

    private fun placeOpen(thoughtId: String) {
        thoughtViews.values.filter { it.thoughtId == thoughtId }.forEach { placeThought(it) }
    }

    private fun shownLibrary(open: OpenThought): Vec3 {
        val thought = brain.graph.find(open.thoughtId) ?: return Vec3(0f, 0f, 0f)
        val roomId = room?.anchor?.uuid?.toString()
        return if (roomId != null && brain.graph.anchorFor(thought.id, roomId) != null) libraryOf(entityWorld(open.entity)) else thought.coordinates
    }

    private fun entityWorld(entity: Entity): Vec3 {
        val local = entity.getComponent<Transform>().transform
        val world = if (room == null) local else roomPose() * local
        return Vec3(world.t.x, world.t.y, world.t.z)
    }

    /** Head pose is only the seated frame. Aiming uses the controller, never a head or hand ray. */
    private fun libraryOf(world: Vec3): Vec3 {
        val head = scene.getViewerPose().removePitchAndRoll()
        val local = (head.inverse() * Pose(Vector3(world.x, world.y, world.z), Quaternion(1f, 0f, 0f, 0f))).t
        return ReadingFrame.library(local.x, local.y, local.z)
    }

    private fun aimThought(origin: Vec3, forward: Vec3) = rayHit(
        origin, forward, thoughtViews.values.map { it.thoughtId to entityWorld(it.entity) }, radius = 0.5f, maxDistance = 6f,
    )

    private fun clusterUnder(origin: Vec3, forward: Vec3): String? {
        val points = brain.graph.clusters().map { cluster ->
            val pose = readingPose(cluster.anchor)
            cluster.id to Vec3(pose.t.x, pose.t.y, pose.t.z)
        }
        return rayHit(origin, forward, points, radius = 0.55f, maxDistance = 8f)
    }

    private fun constellationOpen(rootId: String): Boolean {
        val layout = runCatching { brain.graph.layoutConstellation(rootId) }.getOrNull() ?: return false
        return layout.nodes.all { node ->
            val thought = brain.graph.find(node.thought.id) ?: return false
            thoughtViews.values.any { it.thoughtId == node.thought.id } && (thought.coordinates - node.position).length() < 0.08f
        }
    }

    private fun cancelSceneMotion(announce: Boolean = true) {
        if (motion.returning || motion.kind == MotionKind.RESTORE) return
        commitWhenArrived = null
        if (!motion.active) {
            if (brain.gestures.phase != GesturePhase.IDLE) brain.gestures.cancel()
            tossInFlight = null
            hideProps()
            snapClearFocus()
            thoughtViews.values.forEach { placeThought(it) }
            if (announce) setMessage("Canceled.")
            return
        }
        val progress = motion.frame()?.progress ?: 0f
        if (motion.kind == MotionKind.FOCUS || windowHomes.isNotEmpty()) {
            windowDimFrom = ThoughtPaths.mix(windowDimFrom, windowDimTo, progress)
            windowDimTo = 0f
        }
        onArrived = { finishReturn() }
        motion.reverse()
        if (announce) setMessage("Coming back.")
    }

    private fun finishReturn() {
        val opened = openedForMotion.toList()
        openedForMotion.clear()
        tossInFlight = null
        if (brain.gestures.phase != GesturePhase.IDLE) brain.gestures.cancel()
        opened.forEach { id -> thoughtViews.values.filter { it.thoughtId == id }.toList().forEach { destroyThoughtCard(it) } }
        focusTracks.clear()
        stagingFocus = null
        snapClearFocus()
        thoughtViews.values.forEach { placeThought(it) }
        hideProps()
        refreshThoughtStatus()
        setMessage(returnMessage)
        returnMessage = "Canceled. The thoughts are back where they were."
    }

    private fun abandonMotion() {
        if (!motion.active && brain.gestures.phase == GesturePhase.IDLE && brain.focus == null && stagingFocus == null && windowHomes.isEmpty() && openedForMotion.isEmpty()) return
        commitWhenArrived = null
        onArrived = null
        val opened = openedForMotion.toList()
        openedForMotion.clear()
        tossInFlight = null
        if (brain.gestures.phase != GesturePhase.IDLE) brain.gestures.cancel()
        motion.stop()
        motionClock = 0L
        hideProps()
        opened.forEach { id -> thoughtViews.values.filter { it.thoughtId == id }.toList().forEach { destroyThoughtCard(it) } }
        snapClearFocus()
        thoughtViews.values.forEach { placeThought(it) }
    }

    private fun snapClearFocus() {
        windowHomes.forEach { (id, home) ->
            val window = windows.values.firstOrNull { it.saved.id == id } ?: return@forEach
            window.entity.setComponent(Transform(home))
            window.entity.setComponent(Scale(Vector3(window.saved.size)))
            window.entity.setComponent(Visible(!window.saved.hidden))
        }
        windowHomes.clear()
        windowDimFrom = 0f
        windowDimTo = 0f
        val wasFocused = brain.focus != null || stagingFocus != null
        brain.focus = null
        stagingFocus = null
        focusTracks.clear()
        if (wasFocused) {
            thoughtViews.values.forEach { placeThought(it) }
            thoughtViews.values.forEach { bindThoughtCard(it.slot) }
        }
    }

    /** Controller face buttons only. Hands are skipped, so a pinch cannot sling. */
    private fun sampleControllers() {
        if (!sceneLive.get()) return
        val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }
        for (entity in controllers) {
            val controller = entity.tryGetComponent<Controller>() ?: continue
            if (controller.type == ControllerType.HAND) continue
            if (controller.type != ControllerType.CONTROLLER || !controller.isActive) continue
            val mount = entity.tryGetComponent<AvatarAttachment>()?.type.orEmpty()
            if (mount.endsWith("hand")) continue
            val pressed = controller.buttonState and controller.changedButtons
            if (pressed == 0) continue
            val pose = entity.getComponent<Transform>().transform
            val forward = pose.q * Vector3.Forward
            controllerEdges.add(ControllerEdge(Vec3(pose.t.x, pose.t.y, pose.t.z), Vec3(forward.x, forward.y, forward.z), pressed))
        }
    }

    private fun drainControllers() {
        while (true) {
            val edge = controllerEdges.poll() ?: break
            if (!active || shuttingDown) continue
            val pressed = edge.pressed
            fun down(bit: Int) = (pressed and bit) != 0
            when {
                down(ButtonBits.ButtonB) -> when {
                    motion.kind == MotionKind.RESTORE -> Unit
                    motion.active || brain.gestures.phase != GesturePhase.IDLE -> cancelSceneMotion()
                    brain.focus != null -> exitFocus()
                }
                down(ButtonBits.ButtonA) -> controllerSling(edge)
                down(ButtonBits.ButtonX) -> controllerToss(edge)
                down(ButtonBits.ButtonY) -> controllerConstellation(edge)
                down(ButtonBits.ButtonThumbRClick) -> controllerFocus(edge)
            }
        }
    }

    private fun controllerSling(edge: ControllerEdge) {
        if (motion.kind == MotionKind.SLING && !motion.returning) { requestCommit(); return }
        val id = aimThought(edge.origin, edge.forward) ?: return
        beginSling(id, RaySource.CONTROLLER, libraryOf(edge.origin))
    }

    private fun controllerToss(edge: ControllerEdge) {
        if (motion.kind == MotionKind.TOSS && !motion.returning) { requestCommit(); return }
        val id = aimThought(edge.origin, edge.forward) ?: return
        if (brain.graph.clusters().isEmpty()) { setMessage("Create a named cluster before tossing a thought."); return }
        val preview = runCatching {
            val aimed = clusterUnder(edge.origin, edge.forward)
            if (aimed != null) brain.graph.tossOnto(id, aimed)
            else {
                val ahead = Vec3(edge.origin.x + edge.forward.x, edge.origin.y + edge.forward.y, edge.origin.z + edge.forward.z)
                brain.graph.tossPreview(id, libraryOf(ahead) - libraryOf(edge.origin), null)
            }
        }.getOrElse { setMessage(it.message ?: "No cluster."); return }
        beginToss(id, preview, RaySource.CONTROLLER, withDialog = false)
    }

    private fun controllerConstellation(edge: ControllerEdge) {
        if (motion.active) { setMessage("Finish or cancel the motion in the scene."); return }
        val id = aimThought(edge.origin, edge.forward) ?: brain.selectedId ?: return
        brain.selectedId = id
        if (constellationOpen(id)) collapseSelected() else fanOutNow(id)
    }

    private fun controllerFocus(edge: ControllerEdge) {
        if (motion.kind == MotionKind.FOCUS) { cancelSceneMotion(); return }
        if (motion.kind == MotionKind.RESTORE) return
        if (brain.focus != null) { exitFocus(); return }
        val id = aimThought(edge.origin, edge.forward) ?: return
        brain.selectedId = id
        focusNow(id)
    }

    companion object {
        private const val MANAGER = 7200
        private const val CHAT_BASE = 7300
        private const val THOUGHT_BASE = 7400
        private const val BRAIN_PREF = "second-brain.v1"
        private const val DESK_PREF = "jarvis-desk.v1"
        private const val LINK_BARS = 24
        private const val SCENE_PERMISSION = "com.oculus.permission.USE_SCENE"
        private const val SCENE_REQUEST = 40
        private const val EXPORT = 41
        private const val IMPORT = 42
    }
}
