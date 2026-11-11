# Quest 3 release gates

Owner requirements: own ChatGPT account; passthrough; hand controls; simple conversation; speech-to-text and keyboard; live camera vision and photo upload; flexible window placement; persistent windows belonging to a physical room. Only a finished, verified interface should be pushed as a release.

**Current decision: NOT READY.** The owner subsequently explicitly requested pushing the development build now. Source and a clearly labeled development APK are therefore being pushed on `work/spatial-room-workspace`. This authorization does not change the verification results: do not merge or describe the interface as a finished, fully verified release while the device/account gates remain open.

| Requirement | Implementation / offline evidence | Remaining release gate |
| --- | --- | --- |
| Quest 3 immersive passthrough | Meta Spatial SDK activity, manifest and passthrough enablement; build/lint checks | Launch on the actual Quest 3 and verify passthrough while all panels render |
| Hands and controllers | SDK interaction module, grabbable panels, native UI inputs | Confirm hand ray/pinch, scroll, grab/release, keyboard focus and lock behavior without conflicting gestures |
| Flexible placement | Front/left/right/lower, look-to-surface raycast, resize, follow, lock/unlock and recall | Verify orientation, comfortable depth, wall/table alignment, follow behavior and recovery of out-of-reach panels |
| Room-bound persistence | MRUK UUID plus room-relative pose; codec and actual SDK pose-math tests | Place windows, force-stop/relaunch, recenter/restart the headset, leave/re-enter the room; confirm physical alignment and no other-room restoration |
| Independent windows | Six mixed activity panels, shared WebView account profile, individual URLs/names/options | Confirm separate conversation history/navigation, draft preservation, shared login, hide/show and closing one without affecting another |
| ChatGPT account | Official website, private WebView cookie store, normal sign-in and browser fallback | User sign-in on the device, persistence after restart, chosen OAuth provider compatibility; browser fallback alone does not prove immersive account sign-in |
| Conversation and tools | Official website UI retained; current public composer/dictation/Send selectors supported | Send and receive a real signed-in conversation; exercise the account's required model/tools/apps in the panel |
| Keyboard | Focus bridge plus Android IME/hardware keyboard | System keyboard opening, text editing, submit/cancel and hardware keyboard on Quest |
| STT / voice | Android recognizer insertion and website dictation fallback; microphone request handling | Confirm a speech service or website dictation actually works; approve/deny/revoke mic permissions and check capture stops |
| Camera | Camera2 permissions, private preview stream, YUV_420_888 or JPEG capture; stride/crop packing tests | Verify actual exposed left/right camera IDs, stream configurations, preview/capture orientation, true current images, revocation and lifecycle cleanup |
| Photo upload | Android picker, resizing/orientation handling, website file input; controlled Chromium File/DataTransfer tests | Attach and send real picked/captured photos through the current signed-in ChatGPT page; verify the model sees the intended image |
| Live vision | Configurable periodic images and prompts; tests for draft/busy/upload/stop/navigation/login gates | Confirm real attachments finish before Send, corresponding answers arrive, account limits surface correctly, Stop/hide/background/navigation stop sharing, and no text-only auto-send occurs |
| Performance | Small native shell plus Meta engine; no Unity runtime or local model in spatial module | Six-panel responsiveness, frame timing, memory use, camera/voice coexistence and a sustained headset session |
| Privacy / lifecycle | Capture defaults off, explicit live-session confirmation, bounded sessions, hidden/closed panel stop messages, private receiver controls | Observe actual camera/mic indicators and permission state on close, hide, suspend, sign-out and crash/relaunch |
| Distribution | Build artifacts and debug signature can be checked locally | Stable private signing key and a device-validated install/update path; the development debug key is not a production signing plan |

## Browser/widgets additions in 0.4

Browser URL policy, schema migration, per-widget state validation, AI HTML extraction and protected draft handoff have automated coverage. The actual WidgetDocument HTML renderer and all five templates pass Chromium checks, including opaque-origin isolation, offline network blocking and explicit HTTPS CDN loading.

Native activity-panel embedding for Browser/Widgets, native JSON-state persistence, bookmarks/pickers, focus/hide/restore across processes, and a signed-in AI-reply-to-widget workflow still require the Quest 3. Imported widgets start offline. Browser and Widget processes/profiles must remain isolated from Assistant cookies. These additions do not close any of the physical/account gates above.

See [MUST_HAVE_COVERAGE.md](MUST_HAVE_COVERAGE.md) for the current requirement-by-requirement status and [VERIFICATION_04.md](VERIFICATION_04.md) for the build receipt.

## Acceptance evidence

Record the headset model, Horizon OS build, app commit/hash, successful and failed steps, and any remaining defects. Automated state/pose/packing tests, a controlled-page browser test, and a logged-out public-page check have different scopes; none substitutes for signed-in or physical-device checks.

The current workspace has no authorized ADB device. The user confirmed that the headset is not connected. No authenticated ChatGPT account is available inside the test browser. These are explicit blockers to end-to-end certification, not passing test results.
