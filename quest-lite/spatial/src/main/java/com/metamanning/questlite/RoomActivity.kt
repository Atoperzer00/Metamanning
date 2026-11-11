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
    private var controls: LinearLayout? = null
    private var roomStatus: TextView? = null
    private var message = "Map your room to enable persistent windows."
    private val windows = mutableMapOf<Int, OpenWindow>()
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
                UIPanelSettings(shape = QuadShapeOptions(width = 0.8f, height = 0.95f), display = DpDisplayOptions(600f, 720f), style = PanelStyleOptions(R.style.MetamanningTheme))
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
                    putExtra("window_title", saved.title); putExtra("initial_hidden", saved.hidden); putExtra("initial_url", saved.url)
                }
            }, settingsCreator = {
                UIPanelSettings(shape = QuadShapeOptions(width = 1.1f, height = 0.9f), display = DpDisplayOptions(1000f, 820f), style = PanelStyleOptions(R.style.MetamanningTheme))
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
        // The manager is dynamic: its position is computed from the current viewer on every recall.
        managerEntity = Entity.create(listOf(Panel(MANAGER), Transform(frontPose(-0.95f, 1.4f)), Grabbable()))
        if (checkSelfPermission(SCENE_PERMISSION) == PackageManager.PERMISSION_GRANTED) loadRooms()
        else requestPermissions(arrayOf(SCENE_PERMISSION), SCENE_REQUEST)
    }
    private fun loadRooms() {
        setMessage("Recognizing your mapped room…")
        mruk.loadSceneFromDevice().whenComplete { result, error -> runOnUiThread {
            if (shuttingDown) return@runOnUiThread
            setMessage(if (error != null || result != MRUKLoadDeviceResult.SUCCESS) "Room recognition unavailable. Select Set up room or Reload room." else "Look around to recognize your current room.")
        } }
    }
    private fun updateWorkspace() {
        val viewer = scene.getViewerPose()
        // getCurrentRoom() can fall back to the first room. Never restore private windows on that fallback.
        val recognized = mruk.rooms.firstOrNull { it.entity != null && it.isPositionInRoom(viewer.t) }
        if (recognized?.anchor?.uuid != room?.anchor?.uuid || recognized?.entity?.id != room?.entity?.id) changeRoom(recognized)
        if (room == null) return
        var released = false
        for (window in windows.values) {
            if (window.follow && !window.saved.hidden) {
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
    private fun changeRoom(next: MRUKRoom?) {
        // Do not persist unlocalized transforms, and do not show one room's conversations in another.
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        room = next; roomSaveBlocked = false
        if (next == null) { setMessage("Room not recognized. Saved windows remain hidden until this room is recognized."); refreshControls(); return }
        val roomId = next.anchor.uuid.toString()
        val saved = prefs.getString("layout.$roomId", null)
        if (saved != null) {
            runCatching { RoomLayoutCodec.decode(saved, roomId) }.onSuccess { layout ->
                roomName = layout.name
                layout.windows.forEach { createWindow(it) }
                setMessage("$roomName · restored ${layout.windows.size} room windows")
            }.onFailure {
                roomSaveBlocked = true; roomName = prefs.getString("name.$roomId", "Room") ?: "Room"
                setMessage("Saved layout could not be restored. It has been preserved. Import a backup or explicitly reset this room.")
            }
        } else {
            roomName = prefs.getString("name.$roomId", "Room") ?: "Room"
            setMessage("$roomName recognized. Add a window, place it, and save this room.")
        }
        refreshControls()
    }
    private fun roomPose(): Pose = room?.entity?.getComponent<Transform>()?.transform ?: error("No recognized room")
    private fun roomLocal(world: Pose): Pose = roomPose().inverse() * world
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
            scene.enablePassthrough(passthrough.isChecked); setMessage("Workspace options saved.")
        }.setNegativeButton("Cancel", null).show()
    }
    private fun createWindow(saved: WindowLayout) {
        val parent = room?.entity ?: return
        // Allocate the record before Panel creation; the intent creator reads the stable slot record.
        val entity = Entity.create(listOf(Transform(Pose(Vector3(saved.position[0], saved.position[1], saved.position[2]),
            Quaternion(saved.rotation[0], saved.rotation[1], saved.rotation[2], saved.rotation[3]))),
            TransformParent(parent), Scale(Vector3(saved.size)), Grabbable(enabled = !saved.locked), Visible(!saved.hidden)))
        windows[saved.slot] = OpenWindow(saved, entity)
        entity.setComponent(Panel(CHAT_BASE + saved.slot))
    }
    private fun addWindow(kind: String = "assistant", requestedUrl: String = "https://chatgpt.com/", widgetHtml: String = "") {
        if (room == null) { setMessage("Recognize or set up a room first."); return }
        val limit = prefs.getInt("window-limit", RoomLayoutCodec.MAX_WINDOWS).coerceIn(1, RoomLayoutCodec.MAX_WINDOWS)
        val slot = (0 until limit).firstOrNull { !windows.containsKey(it) }
        if (slot == null) { setMessage("Window limit reached. Close one or increase the limit in Workspace options."); return }
        if (kind == "browser" && !RoomLayoutCodec.safeBrowserUrl(requestedUrl)) { setMessage("Enter a valid HTTPS browser address."); return }
        val offsets = floatArrayOf(0f, 1.2f, -1.2f, 2.4f, -2.4f, 0f)
        val placed = frontPose(offsets[slot], if (slot < 3) 1.5f else 2f)
        if (slot == 5) placed.t.y += 0.95f
        val pose = roomLocal(placed)
        val title = when (kind) { "browser" -> "Browser ${slot + 1}"; "widget" -> "Widget ${slot + 1}"; else -> "Conversation ${slot + 1}" }
        createWindow(WindowLayout(slot = slot, title = title, url = if (kind == "browser") requestedUrl else "https://chatgpt.com/", kind = kind, html = widgetHtml, position = listOf(pose.t.x, pose.t.y, pose.t.z), rotation = listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z)))
        saveRoom(); refreshControls()
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
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ShellStyle.header(context, column)
        roomStatus = TextView(context).apply { setTextColor(Color.parseColor("#B6C9DB")); text = message; setPadding(12, 8, 12, 12) }
        column.addView(roomStatus)
        fun row(vararg actions: Pair<String, () -> Unit>) {
            val line = LinearLayout(context)
            actions.forEach { (label, action) -> line.addView(Button(context).apply { text = label; ShellStyle.button(context, this, label); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, ShellStyle.dp(context, 56), 1f)) }
            column.addView(line)
        }
        row("Set up room" to { mruk.requestSceneCapture().whenComplete { _, _ -> runOnUiThread { loadRooms() } } }, "Reload room" to { requestRoomPermission() })
        row("Assistant" to { addWindow() }, "Browser" to { browserDialog() }, "HTML widget" to { chooseWidget() })
        row("Workspace options" to { workspaceOptions() }, "Save room" to { if (saveRoom()) setMessage("Saved $roomName. Windows will return when this room is recognized.") })
        row("Recall all" to {
            val ordered = windows.values.sortedBy { it.saved.slot }
            val widths = ordered.map { 1.1f * it.saved.size }
            var left = -(widths.sum() + (ordered.size - 1).coerceAtLeast(0) * 0.12f) / 2f
            ordered.forEachIndexed { index, window ->
                window.follow = false; window.saved = window.saved.copy(hidden = false)
                val side = left + widths[index] / 2f; left += widths[index] + 0.12f
                window.entity.setComponent(Transform(roomLocal(frontPose(side, 1.8f))))
                window.entity.setComponent(Visible(true)); window.entity.setComponent(Grabbable(enabled = !window.saved.locked)); control(window, "resume")
            }; saveRoom(); refreshControls()
        }, "Name room" to { editName(roomName, "Room name") { roomName = it; saveRoom(); refreshControls() } })
        row("Closed windows" to { restoreClosedWindow() })
        row("Export layout" to { exportLayout() }, "Import layout" to { importLayout() })
        row("Reset this room" to { confirmReset() }, "Hide menu" to { managerEntity?.setComponent(Visible(false)) })
        controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }; column.addView(controls)
        scroll.addView(column); refreshControls(); return scroll
    }
    private fun refreshControls() {
        val column = controls ?: return
        column.removeAllViews()
        windows.values.sortedBy { it.saved.slot }.forEach { window ->
            val line = LinearLayout(column.context).apply { orientation = LinearLayout.VERTICAL }
            val state = if (window.follow) "follows you" else if (window.saved.hidden) "hidden" else if (window.saved.locked) "pinned and locked" else "room position · movable"
            line.addView(TextView(column.context).apply { text = "${window.saved.title} · ${window.saved.kind} · $state"; setTextColor(Color.WHITE); setPadding(8, 16, 8, 8) })
            val buttons = LinearLayout(column.context)
            fun action(label: String, operation: () -> Unit) { buttons.addView(Button(column.context).apply { text = label; setAllCaps(false); setOnClickListener { operation() } }, LinearLayout.LayoutParams(0, ShellStyle.dp(column.context, 52), 1f)) }
            action("Place") { placementOptions(window) }
            action("Size") { sizeOptions(window) }
            action("Focus") {
                windows.values.forEach { other ->
                    val visible = other.saved.id == window.saved.id
                    other.saved = other.saved.copy(hidden = !visible); other.entity.setComponent(Visible(visible))
                    control(other, if (visible) "resume" else "pause")
                }; saveRoom(); refreshControls()
            }
            action("Rename") { editName(window.saved.title, "Window name") { window.saved = window.saved.copy(title = it); control(window, "rename"); saveRoom(); refreshControls() } }
            action(if (window.saved.hidden) "Show" else "Hide") {
                window.saved = window.saved.copy(hidden = !window.saved.hidden); window.entity.setComponent(Visible(!window.saved.hidden))
                control(window, if (window.saved.hidden) "pause" else "resume"); saveRoom(); refreshControls()
            }
            action("Close") { closeWindow(window.saved.slot) }
            line.addView(buttons); column.addView(line)
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
        windows.values.filter { !it.saved.hidden }.forEach { control(it, "resume") }
    }
    override fun onPause() { active = false; placementHandler.removeCallbacksAndMessages(null); saveRoom(); windows.values.forEach { control(it, "pause") }; super.onPause() }
    override fun onSpatialShutdown() {
        saveRoom(); shuttingDown = true; sceneReady = false
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        super.onSpatialShutdown()
    }
    override fun onDestroy() { documentIo.shutdownNow(); runCatching { unregisterReceiver(events) }; super.onDestroy() }
    companion object {
        private const val MANAGER = 7200
        private const val CHAT_BASE = 7300
        private const val SCENE_PERMISSION = "com.oculus.permission.USE_SCENE"
        private const val SCENE_REQUEST = 40
        private const val EXPORT = 41
        private const val IMPORT = 42
    }
}
