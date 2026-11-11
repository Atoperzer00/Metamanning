# Reused projects and assets

- **JarvisVR** — https://github.com/sumitaich1998/jarvisvr, imported at `4950a4017c6b4d17de60b52edaa26d561e9e41c1`. The complete upstream source and Git history are preserved in this repository. Its MIT license is in the root `LICENSE`.
- **Android Camera Samples** — https://github.com/android/camera-samples at `154c954abc5c80dddf07d264319cf89a3e1ccda9`. `PhotoOrientation.java` adapts the bitmap rotation/mirroring and resource-recycling logic from `core-camera/src/main/java/com/android/camera/core/image/ImageUtils.kt` to Java, adding Android platform EXIF mapping. Copyright 2026 The Android Open Source Project; Apache License 2.0. The source header is retained and the license is bundled in `app/src/main/assets/licenses/Apache-2.0.txt`. The much larger Compose catalog and its dependencies are not shipped in the lightweight app.
- **Lucide** — https://github.com/lucide-icons/lucide, `icons/scan-eye.svg`. The app mark adapts its paths into an Android vector drawable with a colored rounded background. Copyright (c) 2026 Lucide Icons and Contributors; ISC license. Full upstream license text is bundled in `app/src/main/assets/licenses/Lucide.txt`.

**AndroidX ExifInterface 1.4.1** is used under Apache License 2.0 for photo metadata parsing (https://developer.android.com/jetpack/androidx/releases/exifinterface).

The Android companion uses platform WebView and Camera2 APIs. Its website adapter is a Metamanning integration and is not an official OpenAI SDK. No third-party credentials, compiled applications or model weights are copied into this app.

- **Meta Spatial SDK Samples** — https://github.com/meta-quest/Meta-Spatial-SDK-Samples at `822f4d1ec967d4975ae8db386502b60cda5218c0`. Spatial panel registration, passthrough, MRUK room loading, grabbable and room transform patterns informed/adapted into `spatial/src/main/java/com/metamanning/questlite/RoomActivity.kt`. Copyright Meta Platforms, Inc. and affiliates; MIT sample license is bundled as `app/src/main/assets/licenses/Meta-Samples.txt`. The prebuilt SDK 0.15.0 itself is distributed under the Meta Platform Technologies SDK License Agreement, not the sample MIT license. SDK LICENSE/notices files are retained in the spatial app's assets/licenses directory.

The Quest camera reference at https://github.com/Androktasiai-Leyen/quest-anime-filter was inspected for native YUV camera-format behavior; no code or assets were copied from it.
