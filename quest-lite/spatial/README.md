# Metamanning Spatial — development build, verification pending

This is the Quest 3 immersive implementation. It is separate from the earlier 2D companion and has a different application ID (`com.metamanning.spatial`). **It is not a finished or hardware-verified release.** The previously uploaded 0.2.0 APK does not contain this workspace.

## Workspace

See the [0.4 user guide](../../docs/METAMANNING_04_GUIDE.md) for the browser, HTML editor/templates, AI widget creation and closed-window recovery.

The 0.5.1 development source opens the workspace menu and an Assistant panel immediately. If room recognition is unavailable, the panels remain usable in a temporary tracking-space workspace; room-specific saving and recovery become available after a room is recognized. The menu stays visible and can be recentered. This startup change has compiled and passed local tests, but has not yet been checked on a Quest. The downloadable 0.5.0 APK predates this change.

The same APK also exposes **Luma Chat** under Unknown Sources. It opens the existing account-based chat in a standard Quest panel, so a user can still open a conversation if the immersive activity fails. It does not provide room placement or spatial widgets.

- Meta Spatial SDK 0.15 supplies actual immersive passthrough and spatial Android activity panels; the ISDK module and manifest hand-tracking declarations are included.
- Up to six Assistant, Browser and HTML Widget panels. Assistant panels share their ChatGPT profile; Browser and Widget activities run in separate processes/profiles. Each panel retains its own source/URL, name and options.
- Placement presets: front, left, right and lower/desk height. A three-second look-to-place action raycasts against mapped physical surfaces. Wall placements align to the surface; horizontal surfaces support an upright panel above the hit point.
- Grab/unlock, pin/lock, resize, temporary follow, hide/show, rename, close, recall all and recall workspace menu.
- The **Windows** button in each chat recalls the workspace menu. Hidden/closed panels and app suspension stop their camera/live sharing.
- Named room layouts store each panel pose relative to the recognized MRUK room entity, keyed by the room's persistent UUID. They do not restore raw tracking-origin coordinates as physical anchors. Different/unrecognized rooms do not receive another room's windows. Conversation history and draft restoration are handled by the website and require the signed-in acceptance checks. Follow mode is temporary and does not overwrite the saved pinned position.
- JSON layout export/import validates room identity, schema, window count, slot uniqueness, names, URLs, finite transforms and quaternion/size ranges. Import requires confirmation in the same recognized room. Corrupt saved layouts are preserved until an explicit room reset or valid import.
- Per-window options: text zoom, live image interval (5–60 seconds), image limit (1–100), and custom camera question. Manual photos and speech transcripts are reviewed before submission. Live vision remains opt-in periodic image messages through the website UI.

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

The spatial APK is approximately 70 MB in this development configuration because it includes Meta's arm64 native engine/interaction/room libraries. It does not bundle Unity, Compose, downloaded environments, or a local AI model. The earlier 2D companion remains smaller, but has different spatial capabilities. Native symbols are not stripped in the current local build; no final footprint or performance claim is made.

## What still needs the Quest

See [release gates](../../docs/QUEST_RELEASE_GATES.md). Device/API support, real hand input, cross-session physical room recognition, embedded account sign-in, current signed-in ChatGPT attachments/tools, voice-service availability, camera orientation/format/session compatibility, and performance are not established by compilation or mocks.

Run `quest-lite/tools/quest-preflight.sh` on the computer connected to the Quest. It reads device and app status; it does not install apps, grant permissions or read conversations.

The full official ChatGPT website is retained in each Assistant panel. Features and tools follow the signed-in account and website support. Tools available to this cloud coding session are not a newly implemented Quest backend. No private ChatGPT API or credential/session extraction is used.
