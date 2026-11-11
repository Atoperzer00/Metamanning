# 0.4 development verification

Status: installable development build; physical Quest/account acceptance is still open.

- Both Android APKs build with JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21 and Android SDK 35.
- Both lint tasks complete with zero errors (20 warnings in the 2D/shared module and 35 in spatial). Remaining warnings include English resource/localization work, version suggestions, intentional WebView JavaScript/bridge usage and Quest-only ABI/API choices. They are not represented as a zero-warning result.
- 22 JVM tests pass: room layouts, actual SDK pose math, YUV packing, browser/widget schema migration, URL policy, HTML byte limits, bounded JSON state and imported-widget network reset.
- 15 JavaScript adapter tests pass: draft/upload/busy/stop/navigation/login handling, custom live questions, protected tool drafts, latest-reply HTML extraction and oversized UTF-8 rejection.
- Controlled Chromium assistant integration passes with real DOM events and File/DataTransfer.
- The actual Java WidgetDocument renderer passes Chromium isolation and network tests; all five interactive templates pass. The enabled-network test loads the pinned dayjs 1.11.13 public CDN script in an opaque frame. Default-off mode blocks connections. This is a browser check, not an Android native-state bridge test.
- The current public ChatGPT page loads and accepts composer text insertion in a fresh logged-out profile; upload sign-in gating is checked. The passing public probe submits no message or image. It does not verify an authenticated account.
- APK inspection confirms `com.metamanning.spatial`, version code 4 / `0.4.0-dev`, min Android API 34 / target 35. Its debug signing certificate matches the previously supplied 0.3 APK.
- `git diff --check` passes. APK checksums are in `downloads/SHA256SUMS`.

There is no authorized ADB device in this workspace. Hands, real camera orientation/formats, STT availability, actual signed-in uploads/tools, cross-process spatial panels, native widget-state persistence, real room relocalization and performance therefore remain unverified. See [must-have coverage](MUST_HAVE_COVERAGE.md).
