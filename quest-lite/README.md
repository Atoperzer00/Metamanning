# Metamanning Lite (experimental)

A dependency-free Android WebView companion to the imported JarvisVR project for Quest 3/3S. It opens the official ChatGPT website directly, using normal website sign-in and your existing account. No API key, Python services, Unity runtime, model downloads, or injected login scripts are needed for this companion.

## Build and install

GitHub Actions → **Quest Lite APK** → download the `metamanning-lite-debug` artifact from the branch's successful run. Unzip it, enable Quest developer mode/USB debugging, and install with:

```sh
adb install -r app-debug.apk
```

Open **Metamanning Lite** from Unknown Sources. Alternatively, with JDK 17+, Android SDK 35 and Gradle 8.9 installed:

```sh
gradle -p quest-lite assembleDebug lintDebug
adb install -r quest-lite/app/build/outputs/apk/debug/app-debug.apk
```

This deliberately uses the platform WebView with zero app libraries. `targetSdk 32` retains legacy Quest window compatibility; this sideload prototype must be updated and tested against current target requirements before store distribution.

## Account and controls

- Sign into the official ChatGPT page. Cookies persist in this app's private WebView storage. Browser sessions and WebView sessions are separate: the Browser fallback may require its own sign-in.
- **Back**, **ChatGPT**, and **Browser** are large native toolbar controls. Browser opens the official site in the system browser, useful if OAuth, Cloudflare, or voice is incompatible with the headset's WebView. External-provider OAuth does not return a signed-in browser session to the WebView automatically.
- Use the Quest system keyboard or a physical keyboard in the website composer.
- Use the website's attachment control to choose images from Android's document picker. A missing picker produces a visible fallback message. Copy photos to the headset first. No broad storage permission is requested.
- Use the website's voice/dictation control if available. Audio capture is granted only to the HTTPS `chatgpt.com` origin after Android microphone approval. Availability still depends on ChatGPT and the installed WebView; STT is not independently implemented here.
- Quest provides the 2D window's hand-pointer interaction and its placement in the system environment. Enable passthrough in Quest's system UI. This companion does not create an immersive world-anchored panel or implement custom hand gestures.

## Current limits — check before relying on it

**This is a first implementation, not a headset-validated release.** Website sign-in, voice, photo selection, multitasking placement, and hand pointers require a physical Quest test. If website sign-in is blocked in embedded browsers, use the Browser button; no login restrictions are bypassed.

The companion does **not** implement live headset-camera vision or camera capture. Showing passthrough behind a system window does not expose camera frames to ChatGPT. Upstream's native camera code remains in `unity-client/Assets/JarvisVR/Perception/`; its existing vision streaming targets the upstream backend, not your ChatGPT website session. Connecting those systems requires further implementation and device validation. Photo selection works through the official website input; there is no hidden upload or automatic live stream.

## Device smoke check

1. Install, open, sign in; restart and confirm the account session survives.
2. Place the window in passthrough; use hand pointers to focus the composer, type and send a message.
3. Attach a local image, then cancel a second selection; verify neither operation leaves a stuck picker.
4. Try website dictation, approve microphone, and confirm transcription; repeat after denying permission.
5. Open Browser and confirm the fallback loads. Test your chosen sign-in provider there if embedded sign-in fails.
6. Suspend/resume the app and check microphone activity stops when the website releases it; use Android permission settings to revoke access when needed.

## Verification for this first change

- Java source compiled with Eclipse ECJ against Android API 35 signatures and Java 17 (one expected `Activity.onBackPressed` deprecation warning).
- Android manifest parsed successfully; `git diff --check` passed.
- The GitHub APK build/lint workflow is included, but no workflow run was available at implementation time. No APK build, Android lint result, sign-in success, or headset test is claimed yet.
