# Latest Quest 3 development build

[Download Luma Mind Kit 0.6.1 for Quest 3](https://github.com/Atoperzer00/Metamanning/raw/refs/heads/main/downloads/Luma-Mind-Kit-0.6.1-mindkit.apk)

This is the current Mind Kit build: curved surfaces, a world-stable 3D core with three animated orbital rings, Mind Inbox, Today, focus/rest modes, comfort options, and the Horizon panel-launch crash fix. It starts without requiring a room scan. Personal tools keep their data when reopened, including without room recognition. Chat, browser, seven widget templates, camera, reviewed dictation, sampled live vision and existing room controls remain available. Physical rendering, hand input, signed-in voice and room relocalization still need headset acceptance.

Install as an update to preserve local layouts, tool data and sign-in. The current computer's crash-fixed build and Mind Kit builds use the same local signing key. Much older downloads may use a different key. If installation reports a signing mismatch, stop and back up first; uninstalling clears local data. Do not uninstall as the default update procedure.

```sh
adb install -r Luma-Mind-Kit-0.6.1-mindkit.apk
```

You can also install the downloaded APK with Meta Quest Developer Hub. Complete the Quest Guardian boundary setup on the headset, then open **Luma** under Unknown Sources. Copying an APK to headset storage does not install it. See [checksums](SHA256SUMS). This is a development branch artifact, not a certified release.

Choose **Luma** for the immersive experience. **Luma Chat** is the plain chat fallback. In Luma, **About this build** must show `0.6.1-mindkit`. Camera and microphone permissions are requested when using their features; room permission is requested through room setup.

`main` is the canonical source/download. Earlier `work/*` and `upgrade/*` branches are already represented in its history. The 0.6.0 APK is superseded because it omitted the launch fix and reported the old version internally. Use the link above.

To rebuild on this Windows computer, run `quest-lite/tools/package-spatial.ps1`. Add `-Install` to update one connected, authorized Quest. The script runs launch-metadata regression checks, Android compilation, unit tests and lint, names the APK from its actual version, and prints its checksum. It never uninstalls the app. For the full release check also run `npm --prefix quest-lite run test:browser` and `npm --prefix quest-lite run test:widgets`.

---

# Earlier Metamanning downloads

[Luma Spatial 0.5.0 development APK](https://github.com/Atoperzer00/Metamanning/raw/refs/heads/work/spatial-room-workspace/downloads/Luma-Spatial-0.5.0-dev.apk) uses the earlier signing certificate and remains available for reference.

[Download Metamanning Spatial 0.4.0 development APK](https://github.com/Atoperzer00/Metamanning/raw/refs/heads/work/spatial-room-workspace/downloads/Metamanning-Spatial-0.4.0-dev.apk)

This is the latest browser/widgets upgrade. It includes Assistant, Browser and HTML Widget panels, up to six spatial windows, assistant tools, widget templates and closed-window recovery. It is a development build; physical Quest and signed-in account verification remain pending.

Save the APK in your **computer’s Downloads folder**, then install it with Meta Quest Developer Hub. Copying the APK into the Quest's storage folders does not install it. Open **Metamanning Spatial — development** from Unknown Sources.

For ADB users:

```sh
adb install -r Metamanning-Spatial-0.4.0-dev.apk
```

The bundled 0.3 and 0.4 APKs have matching signing certificates, and schema-1 room layouts migrate forward. Keep a layout export before upgrades. Old 0.3 code does not read the new schema-2 browser/widget layouts. Checksums are in [SHA256SUMS](SHA256SUMS).

[0.4 user guide](../docs/METAMANNING_04_GUIDE.md) · [Must-have coverage](../docs/MUST_HAVE_COVERAGE.md)

---

## Earlier builds

# Quest APK downloads

[Download Metamanning Spatial 0.3.0 development APK — Quest 3](https://github.com/Atoperzer00/Metamanning/raw/refs/heads/work/spatial-room-workspace/downloads/Metamanning-Spatial-0.3.0-dev.apk)

This is the newer immersive room-based workspace. It is a development build, with physical Quest and signed-in account verification still pending. Install using Meta Quest Developer Hub, or `adb install -r Metamanning-Spatial-0.3.0-dev.apk`. Open **Metamanning Spatial — development** from Unknown Sources.

The SHA-256 checksum is in [SHA256SUMS](SHA256SUMS).

---

# Quest preview APK

[Download Metamanning Lite 0.2.0](https://github.com/Atoperzer00/Metamanning/raw/refs/heads/upgrade/lightweight-quest/downloads/Metamanning-Lite-0.2.0.apk)

This debug-signed sideload APK was built from source commit `cc421d0`. Source, build receipt, feature limits and device checks are in [quest-lite/README.md](../quest-lite/README.md). The SHA-256 checksum is in [SHA256SUMS](SHA256SUMS).

```sh
adb install -r Metamanning-Lite-0.2.0.apk
```

Enable Quest developer mode and USB debugging first. Open **Metamanning Lite** from Unknown Sources. Physical Quest and signed-in ChatGPT testing remain necessary; do not interpret this preview as a device-tested release. If a previous install has a different debug signing key, uninstall it first (this clears its local account session).
