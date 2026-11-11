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
