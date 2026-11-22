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
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
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
    private var mindScene: MindScene? = null
    private var mindStartedAt = 0L
    private var controls: LinearLayout? = null
    private var roomStatus: TextView? = null
    private var message = "Luma is starting…"
    private val windows = mutableMapOf<Int, OpenWindow>()
    private val attention = WorkspaceSession()
    private var reducedMotion = false
    private var showCore = true
    private val prefs by lazy { getSharedPreferences("room-workspaces-v1", MODE_PRIVATE) }
    private data class OpenWindow(var saved: WindowLayout, val entity: Entity, var follow: Boolean = false, var wasGrabbed: Boolean = false)
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
                UIPanelSettings(
                    shape = CylinderShapeOptions(radius = 1.45f, width = 0.95f, height = 1.1f),
                    display = DpDisplayOptions(720f, 840f),
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                    style = PanelStyleOptions(R.style.MetamanningTheme)
                )
            })
        )
        repeat(RoomLayoutCodec.MAX_WINDOWS) { slot ->
            registrations += IntentPanelRegistration(CHAT_BASE + slot, intentCreator = {
                val saved = requireNotNull(windows[slot]).saved
                val target = when (saved.kind) { "browser" -> BrowserActivity::class.java; "widget" -> WidgetActivity::class.java; else -> MainActivity::class.java }
                Intent(this, target).apply {
                    data = Uri.parse("metamanning://window/${saved.id}")
                    putExtra("spatial_panel", true); putExtra("window_id", saved.id)
                    putExtra("widget_html", saved.html); putExtra("widget_network", saved.network);
                    putExtra("window_title", saved.title); putExtra("initial_hidden", !attention.visible(saved.id, saved.hidden)); putExtra("initial_url", saved.url)
                }
            }, settingsCreator = {
                UIPanelSettings(
                    shape = CylinderShapeOptions(radius = 1.8f, width = 1.1f, height = 0.9f),
                    display = DpDisplayOptions(1000f, 820f),
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                    style = PanelStyleOptions(R.style.MetamanningTheme)
                )
            })
        }
        return registrations
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(events, IntentFilter("$packageName.WORKSPACE_EVENT"), RECEIVER_NOT_EXPORTED)
        systemManager.registerSystem(object : SystemBase() {
            private var last = 0L
            private val queued = AtomicBoolean(false)
            override fun execute() {
                val now = SystemClock.uptimeMillis()
                if (now - last < 33 || !queued.compareAndSet(false, true)) return
                last = now
                runOnUiThread { try { if (sceneReady && active && !shuttingDown) updateWorkspace() } finally { queued.set(false) } }
            }
        })
    }
    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.enablePassthrough(prefs.getBoolean("passthrough", true))
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        sceneReady = true
        reducedMotion = prefs.getBoolean("reduced-motion", false)
        showCore = prefs.getBoolean("show-core", true)
        createMindKit()
        // The manager is dynamic: its position is computed from the current viewer on every recall.
        managerEntity = Entity.create(listOf(Panel(MANAGER), Transform(frontPose(-1.05f, 1.4f)), Grabbable()))
        // Room scanning can take time or be unavailable. Chat must work immediately.
        addWindow("assistant")
        if (checkSelfPermission(SCENE_PERMISSION) == PackageManager.PERMISSION_GRANTED) loadRooms()
        else setMessage("Ready when you are. Room setup is optional; your personal tools save on this headset.")
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
        updateMindKit()
        // getCurrentRoom() can fall back to the first room. Never restore private windows on that fallback.
        val recognized = mruk.rooms.firstOrNull { it.entity != null && it.isPositionInRoom(viewer.t) }
        if (recognized?.anchor?.uuid != room?.anchor?.uuid || recognized?.entity?.id != room?.entity?.id) changeRoom(recognized)
        var released = false
        for (window in windows.values) {
            if (window.follow && attention.visible(window.saved.id, window.saved.hidden)) {
                val target = roomLocal(frontPose(0f, 1.3f))
                val current = window.entity.getComponent<Transform>().transform
                window.entity.setComponent(Transform(current.lerp(target, 0.08f)))
            }
            val grabbed = window.entity.tryGetComponent<Grabbable>()?.isGrabbed == true
            if (window.wasGrabbed && !grabbed) released = true
            window.wasGrabbed = grabbed
        }
        if (released) saveRoom()
    }

    private fun corePose(): Pose = frontPose(0.9f, 1.8f).also { it.t.y += 0.65f }

    private fun createMindKit() {
        mindStartedAt = SystemClock.uptimeMillis()
        mindScene?.destroy()
        val next = MindScene(systemManager) { recallManager() }
        runCatching { next.create(corePose()); next.setVisible(showCore); mindScene = next }
            .onFailure { next.destroy(); mindScene = null; setMessage("Workspace ready. The 3D core could not be loaded.") }
    }

    private fun updateMindKit() {
        val elapsed = (SystemClock.uptimeMillis() - mindStartedAt).toFloat() / 1000f
        mindScene?.setVisible(showCore)
        mindScene?.update(elapsed, reducedMotion || attention.resting)
    }
    private fun changeRoom(next: MRUKRoom?) {
        attention.forgetFocus()
        // Do not persist unlocalized transforms, and do not show one room's conversations in another.
        val temporary = if (room == null && next != null) windows.values.map { window ->
            val pose = snapshot(window)
            window.saved.copy(position = pose.position, rotation = pose.rotation)
        } else emptyList()
        if (room != null) saveRoom()
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        room = next; roomSaveBlocked = false
        if (next == null) {
            addWindow("assistant", reveal = false)
            setMessage("Temporary workspace is ready. Saved room windows return when that room is recognized.")
            refreshControls(); return
        }
        val roomId = next.anchor.uuid.toString()
        val saved = prefs.getString("layout.$roomId", null)
        if (saved != null) {
            runCatching { RoomLayoutCodec.decode(saved, roomId) }.onSuccess { layout ->
                roomName = layout.name
                layout.windows.forEach { createWindow(it) }
                if (windows.isEmpty()) addWindow("assistant", reveal = false)
                setMessage(if (layout.windows.isEmpty()) "$roomName recognized. Your workspace is ready."
                    else "$roomName · restored ${layout.windows.size} room windows")
            }.onFailure {
                roomSaveBlocked = true; roomName = prefs.getString("name.$roomId", "Room") ?: "Room"
                addWindow("assistant", reveal = false)
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
                addWindow("assistant", reveal = false)
                setMessage("$roomName recognized. Your workspace is ready and saved in this room.")
            }
        }
        refreshControls()
    }
    private fun roomPose(): Pose = room?.entity?.getComponent<Transform>()?.transform ?: error("No recognized room")
    private fun roomLocal(world: Pose): Pose = if (room == null) world else roomPose().inverse() * world
    private fun frontPose(side: Float = 0f, distance: Float = 1.3f): Pose {
        val head = scene.getViewerPose().removePitchAndRoll()
        val result = head * Pose(Vector3(side, -0.12f, distance))
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
                addWindow("widget", widgetHtml = html, requestedTitle = if (which == 0) "My widget" else titles[which])
            }.show()
        }.onFailure { setMessage("Widget templates could not be loaded.") }
    }
    private fun routeAssistantDraft(prompt: String) {
        val assistants = windows.values.filter { it.saved.kind == "assistant" }
        if (assistants.isEmpty()) { setMessage("Open an Assistant window first, then use Ask AI again."); return }
        fun deliver(window: OpenWindow) {
            window.saved = window.saved.copy(hidden = false); attention.restore(); applyAttention()
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
        val motion = CheckBox(this).apply { text = "Still core · reduce animation"; isChecked = reducedMotion }; column.addView(motion)
        val core = CheckBox(this).apply { text = "Show the 3D Mind Core"; isChecked = showCore }; column.addView(core)
        AlertDialog.Builder(this).setTitle("Workspace options").setView(column).setPositiveButton("Save") { _, _ ->
            if (limit.progress + 1 < windows.size || windows.keys.any { it > limit.progress }) { setMessage("Close higher-numbered windows before lowering the window limit."); return@setPositiveButton }
            reducedMotion = motion.isChecked; showCore = core.isChecked
            prefs.edit().putInt("window-limit", limit.progress + 1).putBoolean("passthrough", passthrough.isChecked)
                .putBoolean("reduced-motion", reducedMotion).putBoolean("show-core", showCore).apply()
            scene.enablePassthrough(passthrough.isChecked); setMessage("Workspace options saved.")
        }.setNegativeButton("Cancel", null).show()
    }
    private fun createWindow(saved: WindowLayout) {
        val parent = room?.entity
        // Allocate the record before Panel creation; the intent creator reads the stable slot record.
        val entity = Entity.create(mutableListOf(Transform(Pose(Vector3(saved.position[0], saved.position[1], saved.position[2]),
            Quaternion(saved.rotation[0], saved.rotation[1], saved.rotation[2], saved.rotation[3]))),
            Scale(Vector3(saved.size)), Grabbable(enabled = !saved.locked), Visible(attention.visible(saved.id, saved.hidden))).apply {
            if (parent != null) add(TransformParent(parent))
        })
        windows[saved.slot] = OpenWindow(saved, entity)
        entity.setComponent(Panel(CHAT_BASE + saved.slot))
    }
    private fun addWindow(kind: String = "assistant", requestedUrl: String = "https://chatgpt.com/", widgetHtml: String = "", requestedTitle: String? = null, stableId: String? = null, reveal: Boolean = true) {
        val limit = prefs.getInt("window-limit", RoomLayoutCodec.MAX_WINDOWS).coerceIn(1, RoomLayoutCodec.MAX_WINDOWS)
        val slot = (0 until limit).firstOrNull { !windows.containsKey(it) }
        if (slot == null) { setMessage("Window limit reached. Close one or increase the limit in Workspace options."); return }
        if (kind == "browser" && !RoomLayoutCodec.safeBrowserUrl(requestedUrl)) { setMessage("Enter a valid HTTPS browser address."); return }
        val offsets = floatArrayOf(0f, 1.2f, -1.2f, 2.4f, -2.4f, 0f)
        val placed = frontPose(offsets[slot], if (slot < 3) 1.5f else 2f)
        if (slot == 5) placed.t.y += 0.95f
        val pose = roomLocal(placed)
        if (reveal) attention.restore()
        applyAttention()
        val title = requestedTitle ?: when (kind) { "browser" -> "Browser ${slot + 1}"; "widget" -> "Widget ${slot + 1}"; else -> "Conversation ${slot + 1}" }
        createWindow(WindowLayout(id = stableId ?: java.util.UUID.randomUUID().toString(), slot = slot, title = title, url = if (kind == "browser") requestedUrl else "https://chatgpt.com/", kind = kind, html = widgetHtml, position = listOf(pose.t.x, pose.t.y, pose.t.z), rotation = listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z)))
        saveRoom(); refreshControls()
        if (room == null) setMessage("Ready. Personal tool data saves here; room setup remembers window positions.")
    }

    private fun applyAttention() {
        windows.values.forEach { window ->
            val visible = attention.visible(window.saved.id, window.saved.hidden)
            window.entity.setComponent(Visible(visible))
            control(window, if (visible && active) "resume" else "pause")
        }
        refreshControls()
    }

    private fun focusWindow(window: OpenWindow) {
        window.saved = window.saved.copy(hidden = false)
        window.follow = false
        window.entity.setComponent(Transform(roomLocal(frontPose(0f, 1.5f))))
        window.entity.setComponent(Grabbable(enabled = !window.saved.locked))
        attention.focus(window.saved.id); applyAttention()
        saveRoom()
        setMessage("Just ${window.saved.title}. Return to workspace brings your other windows back.")
    }

    private fun openPersonalTool(title: String) {
        val id = WorkspaceSession.kitId(title)
        windows.values.firstOrNull { it.saved.id == id }?.let { focusWindow(it); return }
        runCatching {
            val roomId = room?.anchor?.uuid?.toString()
            val archived = roomId?.let { prefs.getString("archive.$it", null) }?.let { RoomLayoutCodec.decode(it, roomId!!) }
            val prior = archived?.windows?.firstOrNull { it.id == id }
            if (prior != null) {
                restoreArchived(archived, prior)?.let { focusWindow(it) }
                return
            }
            val templates = assets.open("widget-templates.json").bufferedReader().use { org.json.JSONArray(it.readText()) }
            val template = (0 until templates.length()).map { templates.getJSONObject(it) }.first { it.getString("title") == title }
            addWindow("widget", widgetHtml = template.getString("html"), requestedTitle = title, stableId = id)
            windows.values.firstOrNull { it.saved.id == id }?.let { focusWindow(it) }
        }.onFailure { setMessage("Could not open $title. Your saved data is still on this headset.") }
    }

    private fun openConversation() {
        val existing = windows.values.firstOrNull { it.saved.kind == "assistant" }
        if (existing == null) addWindow() else focusWindow(existing)
    }

    private fun returnToWorkspace() {
        attention.restore(); applyAttention()
        setMessage("Your workspace is back. Hidden windows stay hidden until you choose Show.")
    }
    private fun snapshot(window: OpenWindow): WindowLayout {
        val pose = window.entity.getComponent<Transform>().transform
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
        windows.remove(slot); destroyPanel(window)
        if (attention.focusedId == window.saved.id) { attention.forgetFocus(); applyAttention() }
        saveRoom(); refreshControls()
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
                val saved = layout.windows[index]
                if (windows.values.any { it.saved.id == saved.id }) return@setItems
                restoreArchived(layout, saved)
            }.show()
        }.onFailure { setMessage("Closed-window archive could not be read.") }
    }
    private fun restoreArchived(layout: RoomLayout, saved: WindowLayout): OpenWindow? {
        val limit = prefs.getInt("window-limit", RoomLayoutCodec.MAX_WINDOWS).coerceIn(1, RoomLayoutCodec.MAX_WINDOWS)
        val slot = (0 until limit).firstOrNull { !windows.containsKey(it) }
        if (slot == null) { setMessage("Close a window or increase the window limit first."); return null }
        attention.forgetFocus()
        createWindow(saved.copy(slot = slot, hidden = false))
        if (saveRoom()) {
            val remaining = layout.copy(windows = layout.windows.filter { it.id != saved.id }.mapIndexed { i, item -> item.copy(slot = i) })
            prefs.edit().putString("archive.${layout.roomId}", RoomLayoutCodec.encode(remaining)).apply()
        } else setMessage("Opened for this session. The archived copy is kept until this room can save again.")
        applyAttention()
        return windows[slot]
    }
    private fun recallManager() {
        managerEntity?.setComponent(Transform(frontPose(-1.05f, 1.4f)))
        managerEntity?.setComponent(Visible(true))
        mindScene?.recenter(corePose())
    }
    private fun setMessage(value: String) { message = value; roomStatus?.text = value }
    private fun buildControls(context: Context): View {
        val gap = ShellStyle.dp(context, 8)
        val scroll = ScrollView(context).apply { isFillViewport = true; setBackgroundColor(Color.parseColor("#0B1019")) }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ShellStyle.header(context, column)
        column.setPadding(ShellStyle.dp(context, 18), ShellStyle.dp(context, 10), ShellStyle.dp(context, 18), ShellStyle.dp(context, 24))
        val welcome = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ShellStyle.dp(context, 20), ShellStyle.dp(context, 18), ShellStyle.dp(context, 20), ShellStyle.dp(context, 20))
            background = ShellStyle.panel(context, "#152536", "#315266", 20)
        }
        welcome.addView(TextView(context).apply {
            text = "LUMA MIND KIT"; textSize = 12f; letterSpacing = 0.12f
            setTextColor(Color.parseColor("#91F4D4"))
        })
        welcome.addView(TextView(context).apply {
            text = "A little space to think."; textSize = 27f; setTextColor(Color.WHITE)
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
        section("Start here", "One thought. One next step. Your pace.")
        row("Talk with Luma" to { openConversation() })
        row("Capture a thought" to { openPersonalTool("Mind Inbox") }, "Today's one thing" to { openPersonalTool("Today") })
        row("Focus timer" to { openPersonalTool("Focus timer") }, "Rest a moment" to {
            attention.rest(); applyAttention(); setMessage("Resting. Camera sharing stopped; windows are tucked away. End any website voice call before taking a break.")
        })
        row("Return to workspace" to { returnToWorkspace() })
        section("Make room", "Open another conversation, a website or a local tool")
        row("+ Conversation" to { addWindow() })
        row("Browser" to { browserDialog() }, "HTML widget" to { chooseWidget() })
        section("Your windows", "Focus, move, resize or bring a panel back")
        controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(controls)
        row("Bring windows here" to {
            attention.restore()
            val ordered = windows.values.sortedBy { it.saved.slot }
            val widths = ordered.map { 1.1f * it.saved.size }
            var left = -(widths.sum() + (ordered.size - 1).coerceAtLeast(0) * 0.12f) / 2f
            ordered.forEachIndexed { index, window ->
                window.follow = false; window.saved = window.saved.copy(hidden = false)
                val side = left + widths[index] / 2f; left += widths[index] + 0.12f
                window.entity.setComponent(Transform(roomLocal(frontPose(side, 1.8f))))
                window.entity.setComponent(Visible(true)); window.entity.setComponent(Grabbable(enabled = !window.saved.locked)); control(window, "resume")
            }; saveRoom(); refreshControls()
        }, "Recenter menu" to { recallManager() })
        val roomToggle = Button(context).apply { text = "Room setup & more"; ShellStyle.button(context, this, text.toString()) }
        column.addView(roomToggle, LinearLayout.LayoutParams(-1, ShellStyle.dp(context, 58)))
        val roomOptionsStart = column.childCount
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
        val advancedViews = (roomOptionsStart until column.childCount).map { column.getChildAt(it) }
        advancedViews.forEach { it.visibility = View.GONE }
        roomToggle.setOnClickListener {
            val expand = advancedViews.first().visibility == View.GONE
            advancedViews.forEach { it.visibility = if (expand) View.VISIBLE else View.GONE }
            roomToggle.text = if (expand) "Close room options" else "Room setup & more"
        }
        row("Comfort & appearance" to { workspaceOptions() }, "About this build" to {
            AlertDialog.Builder(this).setTitle("Luma Mind Kit ${BuildConfig.VERSION_NAME}")
                .setMessage("Immersive workspace · curved surfaces · 3D Mind Core\n\nPersonal tools save locally on this headset. The Assistant uses your signed-in ChatGPT website. Room placement needs room setup.\n\nThis app does not inherit this coding chat's memory or tools. Camera and microphone sharing start only through their controls.\n\nLatest download: github.com/Atoperzer00/Metamanning")
                .setPositiveButton("Done", null).show()
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
            val state = if (!attention.visible(window.saved.id, window.saved.hidden)) "Tucked away" else if (window.follow) "Follows you" else if (window.saved.locked) "Pinned" else if (room == null) "Movable" else "Saved in this room"
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
                focusWindow(window)
            }
            action(top, "Place") { placementOptions(window) }
            action(top, "Size") { sizeOptions(window) }
            action(bottom, if (attention.visible(window.saved.id, window.saved.hidden)) "Hide" else "Show") {
                window.saved = window.saved.copy(hidden = attention.visible(window.saved.id, window.saved.hidden))
                if (!window.saved.hidden) attention.restore()
                applyAttention(); saveRoom()
            }
            action(bottom, "Rename") { editName(window.saved.title, "Window name") { window.saved = window.saved.copy(title = it); control(window, "rename"); saveRoom(); refreshControls() } }
            action(bottom, "Close") { closeWindow(window.saved.slot) }
            line.addView(top); line.addView(bottom)
            column.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ShellStyle.dp(context, 8) })
        }
    }
    private fun placementOptions(window: OpenWindow) {
        val labels = arrayOf("In front of me", "To my left", "To my right", "Lower / desk height", "Follow me (temporary)", if (window.saved.locked) "Unlock movement" else "Pin and lock in this room", "Place at a surface I look at")
        AlertDialog.Builder(this).setTitle(window.saved.title).setItems(labels) { _, choice ->
            if (choice == 6) {
                placeAtSurface(window); return@setItems
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
                                attention.forgetFocus()
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
        windows.values.filter { attention.visible(it.saved.id, it.saved.hidden) }.forEach { control(it, "resume") }
    }
    override fun onPause() { active = false; placementHandler.removeCallbacksAndMessages(null); saveRoom(); windows.values.forEach { control(it, "pause") }; super.onPause() }
    override fun onSpatialShutdown() {
        saveRoom(); shuttingDown = true; sceneReady = false
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        mindScene?.destroy(); mindScene = null
        managerEntity?.destroy(); managerEntity = null
        super.onSpatialShutdown()
    }
    override fun onDestroy() {
        shuttingDown = true; sceneReady = false; active = false
        placementHandler.removeCallbacksAndMessages(null)
        mindScene?.destroy(); mindScene = null
        documentIo.shutdownNow(); runCatching { unregisterReceiver(events) }; super.onDestroy()
    }
    companion object {
        private const val MANAGER = 7200
        private const val CHAT_BASE = 7300
        private const val SCENE_PERMISSION = "com.oculus.permission.USE_SCENE"
        private const val SCENE_REQUEST = 40
        private const val EXPORT = 41
        private const val IMPORT = 42
    }
}
