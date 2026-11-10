# Metamanning Lite — Quest 3 / 3S

Small native Android companion built alongside the imported JarvisVR Unity project. It uses the official ChatGPT website and your normal account, with no OpenAI API key or separate AI backend. Android platform APIs supply the camera, keyboard and optional speech recognition; the only added runtime library is AndroidX ExifInterface for reliable photo orientation.

## Features and controls

| Feature | Implementation |
| --- | --- |
| Passthrough | Place the app's system window in Quest's passthrough environment. Enable passthrough in system UI. |
| Hand tracking controls | Quest's system hand pointers operate the large native buttons, website and keyboard. Custom immersive gestures remain in the imported Unity client. |
| Conversation | Official signed-in ChatGPT page, including its normal conversation history and model availability. |
| Speech-to-text | **Speak** opens Android's speech recognizer and inserts the returned words into the composer for review. If no speech service is installed, use the website's dictation/voice control, with origin-scoped microphone permission handling. |
| Keyboard | **Keyboard** focuses the website composer and requests the system keyboard; hardware keyboards also work. |
| Live camera preview | **Camera**, grant Android and headset-camera permissions, choose the exposed camera ID. A native Camera2 preview appears above the conversation. |
| Camera snapshot | **Snapshot** captures a real JPEG and attaches it to the current conversation. Review and send it yourself. |
| Sampled live vision | **Live**, then **Start sharing**, sends a camera JPEG plus a brief analysis prompt every 15 seconds, at most 20 successful sends. Frames are skipped while a response or draft is active. **Stop**, navigation, or leaving the app ends sharing. |
| Photo upload | **Photo** selects an existing local image, downsizes it, and attaches it to the conversation. The website's own upload picker also supports multiple selections. |
| Browser fallback | **Browser** opens the official website outside the embedded WebView if sign-in or voice is incompatible. Browser and WebView sessions are separate. Native camera/adapter controls operate inside this app only. |

The native chrome uses a dark palette, large rounded controls, a visible live-sharing indicator, and a Lucide icon. [Third-party sources and licenses](THIRD_PARTY.md) document the reused projects.

The signed-in website adapter uses the visible composer, image file input and Send button. It never extracts credentials, accesses private ChatGPT endpoints, bypasses account limits, or sends images without an explicit Live-sharing session. Manual photos and dictation are not auto-submitted. Images are held in memory rather than written to shared storage.

## Build and install

With JDK 17+, Android SDK 35 and Gradle 8.9:

```sh
gradle -p quest-lite assembleDebug lintDebug
adb install -r quest-lite/app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, a successful **Quest Lite APK** GitHub Actions run provides the `metamanning-lite-debug` artifact. Enable Quest developer mode, allow USB debugging, and open **Metamanning Lite** from Unknown Sources after installation.

This sideload prototype compiles and targets Android API 35. Store distribution and Quest OS compatibility still require device validation. Camera2 headset access requires a supported Horizon OS version and runtime camera permissions; no synthetic images are used if access fails.

## Practical limits

This is an experimental implementation, not a headset-validated release. Signed-in authentication, camera enumeration, Android speech recognition, system hand-pointer interaction, and Quest window behavior must be verified on a physical Quest. Some sign-in providers reject embedded WebViews; the Browser fallback does not share cookies back into this app.

**Live vision is periodic image messages, not continuous video or ChatGPT's native live-video mode.** It consumes the normal image/message allowance of your account. The website adapter depends on the current ChatGPT DOM and may stop working if its composer or attachment UI changes. If upload evidence is missing or submission is disabled, it waits and then stops with a visible status rather than falling back to text-only automatic requests. If there is no compatible input, open the website attachment menu or use its native picker.

The 2D companion uses Quest's existing passthrough/window/hand interfaces. It does not implement a world-anchored immersive chat panel. The imported `unity-client/` preserves JarvisVR's native immersive passthrough, hand and vision work, but its backend vision stream is separate from this account-based companion.

## Verification

Run the dependency-free adapter state tests:

```sh
node --test quest-lite/tests/chatgpt-adapter.test.cjs
```

The Chromium integration check uses Playwright from the development environment (not shipped in the APK):

```sh
node quest-lite/tests/browser-check.cjs
```

It tests a controlled page with real browser file inputs, DataTransfer and contenteditable events. It is not a test against a signed-in ChatGPT account. See the PR for current build/lint results.

## Physical Quest smoke check

1. Install, sign in, restart and confirm the account session persists; test the Browser fallback separately.
2. Enable system passthrough and hand tracking; move the window, select Keyboard, enter and send a message.
3. Select Speak, dictate, review the resulting draft and send. If speech recognition is absent, try the website microphone control and both grant/deny paths.
4. Select Camera, grant permissions and choose the left/right exposed camera; verify that the preview is a real current view. Press Snapshot; inspect the attached image before sending.
5. Select Photo and attach a local image. Cancel another picker and confirm the previous conversation remains usable.
6. Start Live in an empty composer. Confirm an image plus analysis prompt is sent, an answer arrives, and the visible LIVE status updates. Type a draft and confirm subsequent frames are skipped. Press Stop and confirm no further messages are sent.
7. Start Live again, then navigate or leave the app. Confirm camera activity and automatic sharing stop. Revoke camera permission and verify the failure is visible with no synthetic substitute.

## Build receipt (0.2.0)

- `assembleDebug` and `lintDebug`: passed with JDK 17 / Gradle 8.9 / Android API 35. Lint reports zero errors and two warnings (a newer ExifInterface version is available, and JavaScript is enabled for the ChatGPT website).
- Ten adapter behavior tests: passed. Chromium integration check: passed on a controlled page, including real image File/DataTransfer and composer submission.
- APK signature verification: passed (v2 debug signature). APK is approximately 2.3 MB.
- Physical Quest hardware and a signed-in ChatGPT account were not available for validation. Do not treat compilation or controlled-page checks as proof of headset-camera access or current ChatGPT DOM compatibility.
- This is a debug-signed sideload build. Future builds signed with a different debug key require uninstalling the earlier app first; uninstalling clears its local sign-in session. Keep a stable private signing key for regular distribution.
