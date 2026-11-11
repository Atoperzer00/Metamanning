# Latest Quest 3 development build

[Download Luma Spatial 0.5.3 development APK](https://github.com/Atoperzer00/Metamanning/raw/refs/heads/work/luma-roomless-startup/downloads/Luma-Spatial-0.5.3-dev.apk)

This build opens the redesigned workspace menu and an Assistant panel without waiting for room recognition. It also includes a separate **Luma Chat** standard-panel launcher and an experimental **Voice** button for ChatGPT's signed-in website voice control. **Speak** remains reviewed dictation. Physical Quest rendering, sign-in and voice acceptance remain pending.

The 0.5.3 development APK has a different debug signing certificate from 0.5.0. Since you approved a fresh install, export any useful room layouts first, then uninstall the old package and install this APK from your computer. Uninstalling clears Luma's local layouts and embedded sign-in, but not your ChatGPT account conversations.

```sh
adb uninstall com.metamanning.spatial
adb install Luma-Spatial-0.5.3-dev.apk
```

You can also install the downloaded APK with Meta Quest Developer Hub. Complete the Quest Guardian boundary setup on the headset, then open **Luma** under Unknown Sources. Copying an APK to headset storage does not install it. See [checksums](SHA256SUMS). This is a development branch artifact, not a certified release.

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
