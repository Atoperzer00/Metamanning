# Latest Quest 3 download

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
