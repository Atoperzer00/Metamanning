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
    private val thoughtViews = mutableMapOf<Int, OpenThought>()
    private var thoughtStatus: TextView? = null
    private var environmentSwatch: TextView? = null
    private var scrollQuietUntil = 0L
    private var slingSlot: Int? = null
    private var slingSpring = SpringBody(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
    private var slingTarget = Vec3(0f, 0f, 0f)
    private val prefs by lazy { getSharedPreferences("room-workspaces-v1", MODE_PRIVATE) }
    private data class OpenWindow(var saved: WindowLayout, val entity: Entity, var follow: Boolean = false, var wasGrabbed: Boolean = false)
    private data class OpenThought(val thoughtId: String, val slot: Int, val entity: Entity, var titleView: TextView? = null, var detailView: TextView? = null)
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
                UIPanelSettings(shape = QuadShapeOptions(width = 0.46f, height = 0.28f), display = DpDisplayOptions(460f, 280f), style = PanelStyleOptions(R.style.MetamanningTheme))
            })
        }
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
            private var lastExecute = 0L
            private val queued = AtomicBoolean(false)
            override fun execute() {
                val now = SystemClock.uptimeMillis()
                val frameDt = if (lastExecute == 0L) 0f else (now - lastExecute).toFloat()
                lastExecute = now
                if (now - last < 33 || !queued.compareAndSet(false, true)) return
                last = now
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
            val selection = runCatching { EnvironmentCatalog.select(savedEnvironment, null) }.getOrElse { EnvironmentCatalog.select("passthrough", null) }
            brain.environmentId = selection.active.id
            scene.enablePassthrough(selection.active.passthrough)
        }
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        loadBrain()
        sceneReady = true
        // The manager is dynamic: its position is computed from the current viewer on every recall.
        managerEntity = Entity.create(listOf(Panel(MANAGER), Transform(frontPose(-1.05f, 1.4f)), Grabbable()))
        // Room scanning can take time or be unavailable. Chat must work immediately.
        addWindow("assistant")
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
        stepSling()
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
            addWindow("assistant")
            setMessage("Temporary workspace is ready. Saved room windows return when that room is recognized.")
            refreshControls(); relayoutThoughts(); return
        }
        val roomId = next.anchor.uuid.toString()
        val saved = prefs.getString("layout.$roomId", null)
        if (saved != null) {
            runCatching { RoomLayoutCodec.decode(saved, roomId) }.onSuccess { layout ->
                roomName = layout.name
                layout.windows.forEach { createWindow(it) }
                if (windows.isEmpty()) addWindow("assistant")
                setMessage(if (layout.windows.isEmpty()) "$roomName recognized. Your workspace is ready."
                    else "$roomName · restored ${layout.windows.size} room windows")
            }.onFailure {
                roomSaveBlocked = true; roomName = prefs.getString("name.$roomId", "Room") ?: "Room"
                addWindow("assistant")
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
                addWindow("assistant")
                setMessage("$roomName recognized. Your workspace is ready and saved in this room.")
            }
        }
        refreshControls()
        relayoutThoughts()
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
        val placed = frontPose(offsets[slot], if (slot < 3) 1.5f else 2f)
        if (slot == 5) placed.t.y += 0.95f
        val pose = roomLocal(placed)
        val title = when (kind) { "browser" -> "Browser ${slot + 1}"; "widget" -> "Widget ${slot + 1}"; else -> "Conversation ${slot + 1}" }
        createWindow(WindowLayout(slot = slot, title = title, url = if (kind == "browser") requestedUrl else "https://chatgpt.com/", kind = kind, html = widgetHtml, position = listOf(pose.t.x, pose.t.y, pose.t.z), rotation = listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z)))
        saveRoom(); refreshControls()
        if (room == null) setMessage("Temporary workspace ready. Set up a room when you want windows to return here later.")
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
                window.entity.setComponent(Transform(roomLocal(frontPose(side, 1.8f))))
                window.entity.setComponent(Visible(true)); window.entity.setComponent(Grabbable(enabled = !window.saved.locked)); control(window, "resume")
            }; saveRoom(); refreshControls()
        }, "Recenter menu" to { recallManager() })
        section("Second brain", "Local thoughts. A panel is only a view of one.")
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
    override fun onPause() { active = false; placementHandler.removeCallbacksAndMessages(null); saveRoom(); saveBrain(); windows.values.forEach { control(it, "pause") }; super.onPause() }
    override fun onSpatialShutdown() {
        saveRoom(); saveBrain(); shuttingDown = true; sceneReady = false
        windows.values.toList().forEach { destroyPanel(it) }; windows.clear()
        super.onSpatialShutdown()
    }
    override fun onDestroy() { documentIo.shutdownNow(); runCatching { unregisterReceiver(events) }; super.onDestroy() }
    private fun brainProvenance(note: String) = Provenance("user", "local", System.currentTimeMillis(), note)

    private fun loadBrain() {
        val saved = prefs.getString(BRAIN_PREF, null) ?: return
        runCatching { brain.loadJson(saved) }.onFailure { setMessage("Saved thoughts were preserved but could not be read.") }
    }

    private fun saveBrain() {
        if (shuttingDown) return
        runCatching { prefs.edit().putString(BRAIN_PREF, ThoughtGraph.encode(brain.graph)).apply() }
            .onFailure { setMessage("Thoughts could not be saved: ${it.message}") }
    }

    private fun selectedThought(): Thought? {
        val id = brain.selectedId ?: run { setMessage("Select a thought first."); return null }
        return brain.graph.find(id) ?: run { brain.selectedId = null; setMessage("That thought is no longer in the library."); return null }
    }

    private fun refreshThoughtStatus() {
        val selected = brain.selectedId?.let { brain.graph.find(it) }
        val env = EnvironmentCatalog.curated().firstOrNull { it.id == brain.environmentId }
        val flags = listOfNotNull(
            env?.name,
            if (brain.look.indicatorVisible) "CAPTURE ON" else null,
            if (brain.focus != null) "Focus" else null,
            brain.share?.let { if (it.role == SessionRole.HOST) "Host" else "Guest" },
        ).joinToString(" · ")
        val who = if (selected == null) "No thought selected" else "${selected.title} · ${selected.type.name.lowercase()} · ${selected.privacy.name.lowercase()}"
        thoughtStatus?.text = "$flags\n$who"
    }

    private fun applyEnvironmentChrome() {
        val env = EnvironmentCatalog.curated().firstOrNull { it.id == brain.environmentId } ?: return
        environmentSwatch?.text = env.name
        environmentSwatch?.setBackgroundColor(Color.parseColor(env.backgroundHex))
        environmentSwatch?.setTextColor(Color.parseColor(if (env.id == "design-studio") "#1B2430" else "#F2F6FC"))
    }

    private fun selectEnvironment(id: String) {
        val measured = if (id != "passthrough" && brain.frames.ready()) brain.frames.average() else null
        val selection = runCatching { EnvironmentCatalog.select(id, measured) }.getOrElse { setMessage(it.message ?: "That environment is not available."); return }
        brain.environmentId = selection.active.id
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
            setPadding(ShellStyle.dp(context, 14), ShellStyle.dp(context, 12), ShellStyle.dp(context, 14), ShellStyle.dp(context, 12))
            background = ShellStyle.panel(context, "#121E2D", "#315266", 16)
        }
        val title = TextView(context).apply { textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) }
        val detail = TextView(context).apply { textSize = 13f; setTextColor(Color.parseColor("#A5BDD1")); setPadding(0, 0, 0, ShellStyle.dp(context, 6)) }
        open?.titleView = title
        open?.detailView = detail
        bindThoughtCard(slot)
        column.addView(title); column.addView(detail)
        column.addView(Button(context).apply {
            text = "Select"; ShellStyle.button(context, this, "Select")
            setOnClickListener {
                val thoughtId = thoughtViews[slot]?.thoughtId ?: return@setOnClickListener
                brain.selectedId = thoughtId; refreshThoughtStatus(); setMessage("Selected ${brain.graph.find(thoughtId)?.title ?: "thought"}.")
            }
        })
        return column
    }

    private fun bindThoughtCard(slot: Int) {
        val open = thoughtViews[slot] ?: return
        val thought = brain.graph.find(open.thoughtId) ?: return
        open.titleView?.text = thought.title
        open.detailView?.text = "${thought.type.name.lowercase()} · ${thought.privacy.name.lowercase()}"
    }

    private fun placeThought(open: OpenThought) {
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
        if (brain.gestures.phase != GesturePhase.IDLE) { setMessage("Finish or cancel the current gesture."); return }
        val open = openThoughtCard(thought.id) ?: return
        val preview = brain.gestures.menuPreview(GestureKind.WEB_SLING, thought.id, SystemClock.uptimeMillis() < scrollQuietUntil, anythingGrabbed())
        if (!preview.ok) { setMessage(preview.reason); return }
        val targetPose = roomLocal(frontPose(0f, PullPolicy.DISTANCE_M))
        slingTarget = Vec3(targetPose.t.x, targetPose.t.y, targetPose.t.z)
        val current = open.entity.getComponent<Transform>().transform
        slingSpring = SpringBody(Vec3(current.t.x, current.t.y, current.t.z), Vec3(0f, 0f, 0f))
        slingSlot = open.slot
        AlertDialog.Builder(this).setTitle("Pull ${thought.title} here?")
            .setMessage("The tether is a preview. Commit keeps it at reading distance. Cancel puts it back. Scrolling or grabbing cancels it.")
            .setPositiveButton("Commit") { _, _ -> commitSling(thought.id) }
            .setNegativeButton("Cancel") { _, _ -> cancelSling() }
            .setOnCancelListener { cancelSling() }
            .show()
    }

    private fun stepSling() {
        val slot = slingSlot ?: return
        if (brain.gestures.phase != GesturePhase.PREVIEW || brain.gestures.kind != GestureKind.WEB_SLING) return
        val open = thoughtViews[slot] ?: return
        val interrupted = brain.gestures.interrupt(SystemClock.uptimeMillis() < scrollQuietUntil, anythingGrabbed())
        if (brain.gestures.phase == GesturePhase.IDLE) {
            slingSlot = null; placeThought(open); setMessage(interrupted.reason); return
        }
        slingSpring = TetherSpring.step(slingSpring, slingTarget, 0.033f)
        val position = slingSpring.position
        val rotation = open.entity.getComponent<Transform>().transform.q
        open.entity.setComponent(Transform(Pose(Vector3(position.x, position.y, position.z), rotation)))
    }

    private fun commitSling(thoughtId: String) {
        val result = brain.gestures.commit(SystemClock.uptimeMillis() < scrollQuietUntil, anythingGrabbed())
        slingSlot = null
        val open = thoughtViews.values.firstOrNull { it.thoughtId == thoughtId }
        if (!result.committed) { if (open != null) placeThought(open); setMessage(result.reason); return }
        val roomId = room?.anchor?.uuid?.toString()
        runCatching {
            if (roomId != null && brain.graph.anchorFor(thoughtId, roomId) != null) {
                val pose = roomLocal(frontPose(0f, PullPolicy.DISTANCE_M))
                brain.graph.anchor(thoughtId, roomId, Vec3(pose.t.x, pose.t.y, pose.t.z), listOf(pose.q.w, pose.q.x, pose.q.y, pose.q.z), brainProvenance("sling"))
            } else brain.graph.move(thoughtId, Vec3(0f, 0f, 0f), brainProvenance("sling"))
        }.onSuccess {
            saveBrain(); if (open != null) placeThought(open); setMessage("Committed the pull. Undo returns the thought.")
        }.onFailure { if (open != null) placeThought(open); setMessage(it.message ?: "The pull was not saved.") }
        refreshThoughtStatus()
    }

    private fun cancelSling() {
        if (brain.gestures.phase == GesturePhase.IDLE && slingSlot == null) return
        brain.gestures.cancel()
        val open = slingSlot?.let { thoughtViews[it] }
        slingSlot = null
        if (open != null) placeThought(open)
        setMessage("Canceled. The thought stayed where it was.")
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
        AlertDialog.Builder(this).setTitle("Toss ${thought.title}").setItems(clusters.map { it.name }.toTypedArray()) { _, index ->
            val preview = runCatching { brain.graph.tossOnto(thought.id, clusters[index].id) }.getOrElse { setMessage(it.message ?: "No cluster."); return@setItems }
            val gesture = brain.gestures.menuPreview(GestureKind.TOSS, thought.id, SystemClock.uptimeMillis() < scrollQuietUntil, anythingGrabbed())
            if (!gesture.ok) { setMessage(gesture.reason); return@setItems }
            AlertDialog.Builder(this).setTitle("Toss onto ${preview.clusterName}?")
                .setMessage("It lands on that cluster and stays in the library. Nothing is deleted.")
                .setPositiveButton("Commit") { _, _ ->
                    val committed = brain.gestures.commit(SystemClock.uptimeMillis() < scrollQuietUntil, anythingGrabbed())
                    if (!committed.committed) { setMessage(committed.reason); return@setPositiveButton }
                    runCatching { brain.graph.commitToss(preview, brainProvenance("toss")) }.onSuccess {
                        saveBrain(); relayoutThoughts(); setMessage("Tossed onto ${preview.clusterName}. Undo puts it back.")
                    }.onFailure { brain.gestures.cancel(); setMessage(it.message ?: "Toss did not finish.") }
                }.setNegativeButton("Cancel") { _, _ -> brain.gestures.cancel(); setMessage("Toss canceled. Nothing was deleted.") }
                .show()
        }.show()
    }

    private fun fanOut() {
        val thought = selectedThought() ?: return
        val layout = runCatching { brain.graph.layoutConstellation(thought.id) }.getOrElse { setMessage(it.message ?: "Could not lay out links."); return }
        val extra = if (layout.omitted > 0) " ${layout.omitted} more stay in the library." else ""
        AlertDialog.Builder(this).setTitle("Fan out ${thought.title}?").setMessage("${layout.nodes.size} cards.$extra")
            .setPositiveButton("Fan out") { _, _ ->
                runCatching { brain.graph.commitConstellation(thought.id, brainProvenance("constellation")) }.onSuccess { committed ->
                    saveBrain(); committed.nodes.forEach { openThoughtCard(it.thought.id) }; brain.selectedId = thought.id
                    setMessage("Fanned out ${committed.nodes.size} thoughts.$extra Undo gathers their positions back.")
                }.onFailure { setMessage(it.message ?: "Constellation was not opened.") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun collapseSelected() {
        if (brain.focus != null) { setMessage("Exit focus before collapsing."); return }
        val thought = selectedThought() ?: return
        thoughtViews.values.filter { it.thoughtId != thought.id }.toList().forEach { destroyThoughtCard(it) }
        openThoughtCard(thought.id)
        setMessage("Collapsed to one card. Linked thoughts remain in the library.")
    }

    private fun enterFocus() {
        val thought = selectedThought() ?: return
        if (brain.focus != null) { setMessage("Exit focus first."); return }
        AlertDialog.Builder(this).setTitle("Focus on ${thought.title}?")
            .setMessage("Other cards and windows dim until you exit. Nothing is deleted.")
            .setPositiveButton("Focus") { _, _ ->
                brain.graph.layoutConstellation(thought.id).nodes.forEach { openThoughtCard(it.thought.id) }
                val cards = thoughtViews.values.associate { it.thoughtId to true }
                val windowVisibility = windows.values.associate { it.saved.id to !it.saved.hidden }
                brain.focus = brain.graph.planFocus(thought.id, cards, windowVisibility)
                applyFocus(false)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun exitFocus() {
        if (brain.focus == null) { setMessage("Focus is already off."); return }
        applyFocus(true)
    }

    private fun applyFocus(restore: Boolean) {
        val bubble = brain.focus ?: return
        if (restore) {
            thoughtViews.values.forEach { open -> open.entity.setComponent(Visible(bubble.previousCards[open.thoughtId] ?: true)) }
            windows.values.forEach { window ->
                window.entity.setComponent(Visible(!window.saved.hidden))
                control(window, if (window.saved.hidden) "pause" else "resume")
            }
            brain.focus = null
            setMessage("Focus cleared. Everything is where you left it.")
        } else {
            thoughtViews.values.forEach { open -> open.entity.setComponent(Visible(bubble.cardVisibility[open.thoughtId] ?: false)) }
            windows.values.forEach { window ->
                val visible = bubble.windowVisibility[window.saved.id] ?: false
                window.entity.setComponent(Visible(visible))
                control(window, if (visible) "resume" else "pause")
            }
            setMessage("Focused. Exit focus brings everything back.")
        }
        refreshThoughtStatus()
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
        if (brain.focus != null) applyFocus(true)
        if (brain.graph.undo() == null) { setMessage("Nothing to undo."); return }
        saveBrain(); relayoutThoughts(); refreshThoughtStatus(); setMessage("Undid the last change.")
    }

    private fun showHistory() {
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
                    if (brain.focus?.rootId == thought.id) applyFocus(true)
                    thoughtViews.values.filter { it.thoughtId == thought.id }.toList().forEach { destroyThoughtCard(it) }
                    saveBrain(); refreshThoughtStatus(); setMessage("Deleted ${thought.title}. Undo can restore it.")
                }.onFailure { setMessage(it.message ?: "Thought was not deleted.") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startCoThink() {
        AlertDialog.Builder(this).setTitle("Host a co-think session?")
            .setMessage("Nothing is shared until you approve specific thoughts. Conversations, camera snapshots, and cookies cannot be approved. This prepares a local share; it does not open a network stream.")
            .setPositiveButton("Host") { _, _ ->
                brain.share = CoThinkSession(UUID.randomUUID().toString(), SessionRole.HOST)
                refreshThoughtStatus(); setMessage("You are the host. Approve thoughts to prepare a share.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun approveShare() {
        val session = brain.share ?: run { setMessage("Host a co-think session first."); return }
        if (session.role != SessionRole.HOST) { setMessage("Guests cannot approve a share."); return }
        val eligible = brain.graph.thoughts().filter { canShare(it) }
        if (eligible.isEmpty()) { setMessage("No shareable thoughts. Conversations stay private."); return }
        val checked = BooleanArray(eligible.size)
        AlertDialog.Builder(this).setTitle("Approve thoughts").setMultiChoiceItems(eligible.map { it.title }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Approve") { _, _ ->
                val ids = eligible.filterIndexed { index, _ -> checked[index] }.map { it.id }
                if (ids.isEmpty()) { setMessage("No thoughts approved. Nothing was shared."); return@setPositiveButton }
                runCatching {
                    session.approve(brain.graph, ids)
                    prefs.edit().putString(SHARE_PREF, encodeEnvelope(session.envelope(brain.graph))).apply()
                }.onSuccess { setMessage("Approved ${ids.size} thoughts. They stay on this headset until a guest imports the prepared share.") }
                    .onFailure { setMessage(it.message ?: "Those thoughts cannot be shared.") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun joinCoThink() {
        val json = prefs.getString(SHARE_PREF, null) ?: run { setMessage("No prepared share on this headset."); return }
        val envelope = runCatching { decodeEnvelope(json) }.getOrElse { setMessage("Prepared share could not be read."); return }
        AlertDialog.Builder(this).setTitle("Join as guest?").setMessage("Import ${envelope.thoughts.size} approved thoughts. Private notes and conversations already here stay put.")
            .setPositiveButton("Import") { _, _ ->
                brain.share = CoThinkSession(envelope.sessionId, SessionRole.GUEST)
                runCatching { importEnvelope(brain.graph, envelope, Provenance("import", "co-think-guest", System.currentTimeMillis(), "guest import")) }.onSuccess {
                    saveBrain(); refreshThoughtStatus(); setMessage("Imported the approved thoughts. Nothing else on this headset was replaced.")
                }.onFailure { setMessage(it.message ?: "The share was not imported.") }
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
            CapturePhase.ARMED -> AlertDialog.Builder(this).setTitle("CAPTURE ON")
                .setMessage("Keep this view as a local reference? The camera stream is not uploaded.")
                .setPositiveButton("Keep") { _, _ ->
                    brain.look.capture("local-snapshot:${UUID.randomUUID()}")
                    setMessage("Snapshot kept on this headset. Submit a question or cancel."); refreshThoughtStatus()
                }.setNegativeButton("Cancel capture") { _, _ -> brain.look.cancel(); setMessage("Capture canceled."); refreshThoughtStatus() }.show()
            CapturePhase.CAPTURED -> {
                val field = EditText(this).apply { hint = "What is this?"; isSingleLine = true }
                AlertDialog.Builder(this).setTitle("Question about the snapshot").setView(field)
                    .setPositiveButton("Review") { _, _ ->
                        val request = runCatching { brain.look.submit(field.text.toString()) }.getOrElse { setMessage(it.message ?: "Question was not submitted."); return@setPositiveButton }
                        routeAssistantDraft("I am looking at something nearby (${request.snapshotRef}). ${request.prompt}\n\nPrepared on device. This does not call ChatGPT tools. Send only if you want this question in the conversation.")
                        setMessage("Question offered to an assistant window. Nothing was sent automatically."); refreshThoughtStatus()
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

    companion object {
        private const val MANAGER = 7200
        private const val CHAT_BASE = 7300
        private const val THOUGHT_BASE = 7400
        private const val BRAIN_PREF = "second-brain.v1"
        private const val SHARE_PREF = "second-brain.envelope"
        private const val SCENE_PERMISSION = "com.oculus.permission.USE_SCENE"
        private const val SCENE_REQUEST = 40
        private const val EXPORT = 41
        private const val IMPORT = 42
    }
}
