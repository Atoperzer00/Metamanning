# Metamanning Spatial — development build, verification pending

This is the Quest 3 immersive implementation. It is separate from the earlier 2D companion and has a different application ID (`com.metamanning.spatial`). **It is not a finished or hardware-verified release.** The previously uploaded 0.2.0 APK does not contain this workspace.

## Workspace

See the [0.4 user guide](../../docs/METAMANNING_04_GUIDE.md) for the browser, HTML editor/templates, AI widget creation and closed-window recovery.

The 0.5.3 development source opens passthrough, a small menu, and several linked thought cards at reading distance. Pull here, Recall, Fan out, and Focus are on each card. A conversation panel is opened from the menu, not at launch. If room recognition is unavailable, the thoughts remain usable in a temporary tracking-space workspace; room-specific saving and recovery become available after a room is recognized. This startup change has compiled and passed local tests, but has not yet been checked on a Quest. The downloadable 0.5.0 APK predates this change.

The same APK also exposes **Luma Chat** under Unknown Sources. It opens the existing account-based chat in a standard Quest panel, so a user can still open a conversation if the immersive activity fails. It does not provide room placement or spatial widgets.

Assistant panels now have a **Voice** button that opens a visible voice control on the signed-in ChatGPT page, when one is available. **Speak** remains speech-to-text draft input. ChatGPT voice availability and microphone behavior in Quest WebView still require a signed-in headset test; an unavailable control produces a visible explanation rather than claiming a voice session started.

- Meta Spatial SDK 0.15 supplies actual immersive passthrough and spatial Android activity panels; the ISDK module and manifest hand-tracking declarations are included.
- Up to six Assistant, Browser and HTML Widget panels. Assistant panels share their ChatGPT profile; Browser and Widget activities run in separate processes/profiles. Each panel retains its own source/URL, name and options.
- Placement presets: front, left, right and lower/desk height. A three-second look-to-place action raycasts against mapped physical surfaces. Wall placements align to the surface; horizontal surfaces support an upright panel above the hit point.
- Grab/unlock, pin/lock, resize, temporary follow, hide/show, rename, close, recall all and recall workspace menu.
- The **Windows** button in each chat recalls the workspace menu. Hidden/closed panels and app suspension stop their camera/live sharing.
- Named room layouts store each panel pose relative to the recognized MRUK room entity, keyed by the room's persistent UUID. They do not restore raw tracking-origin coordinates as physical anchors. Different/unrecognized rooms do not receive another room's windows. Conversation history and draft restoration are handled by the website and require the signed-in acceptance checks. Follow mode is temporary and does not overwrite the saved pinned position.
- JSON layout export/import validates room identity, schema, window count, slot uniqueness, names, URLs, finite transforms and quaternion/size ranges. Import requires confirmation in the same recognized room. Corrupt saved layouts are preserved until an explicit room reset or valid import.
- Per-window options: text zoom, live image interval (5–60 seconds), image limit (1–100), and custom camera question. Manual photos and speech transcripts are reviewed before submission. Live vision remains opt-in periodic image messages through the website UI.
- Second brain: a local thought library separate from the six windows, with note cards, search, links, toss-to-cluster, sling preview, focus, undo, recall to reading distance, per-idea history, and room anchors. Desk, beside Recall, arranges open thoughts in the Jarvis arc, grid, or stack and keeps a focus timer and task checklist on the headset. Passthrough remains the default scene. Observatory and Design studio are curated packages (level of detail, lighting, occlusion, license, and a frame-time gate), not color presets, and there is no environment marketplace. Co-think is a host/guest session that sends only approved thoughts; conversations, cookies, camera references, and room anchors stay behind. That exchange is covered between two local sessions, not two physical headsets. Look-and-ask keeps a snapshot of a selected thought on the headset and only offers a reviewed draft. None of this has been exercised on a headset.

## Build and automated checks

JDK 17, Android SDK 35, Gradle 8.13. AGP 8.13.2 and Kotlin 2.2.21 are pinned to handle the SDK's Kotlin metadata in both compilation and lint.

```sh
gradle -p quest-lite :spatial:assembleDebug :spatial:testDebugUnitTest :spatial:lintDebug
npm ci --prefix quest-lite
npm --prefix quest-lite test
npm --prefix quest-lite run test:browser
npm --prefix quest-lite run test:widgets
# Optional live, logged-out compatibility check. Never submits a message or image.
npm --prefix quest-lite run test:public
```

The spatial APK is approximately 70 MB in this development configuration because it includes Meta's arm64 native engine/interaction/room libraries. It does not bundle Unity, Compose, third-party environment meshes, or a local AI model. Two small original environment packages are included on the curated channel. The earlier 2D companion remains smaller, but has different spatial capabilities. Native symbols are not stripped in the current local build; no final footprint or performance claim is made.

## What still needs the Quest

See [release gates](../../docs/QUEST_RELEASE_GATES.md). Device/API support, real hand input, cross-session physical room recognition, embedded account sign-in, current signed-in ChatGPT attachments/tools, voice-service availability, camera orientation/format/session compatibility, and performance are not established by compilation or mocks.

Run `quest-lite/tools/quest-preflight.sh` on the computer connected to the Quest. It reads device and app status; it does not install apps, grant permissions or read conversations.

The full official ChatGPT website is retained in each Assistant panel. Features and tools follow the signed-in account and website support. Tools available to this cloud coding session are not a newly implemented Quest backend. No private ChatGPT API or credential/session extraction is used.
