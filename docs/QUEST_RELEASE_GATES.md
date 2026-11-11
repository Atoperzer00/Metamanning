# Quest 3 release gates

Owner requirements: own ChatGPT account; passthrough; hand controls; simple conversation; speech-to-text and keyboard; live camera vision and photo upload; flexible window placement; persistent windows belonging to a physical room. Only a finished, verified interface should be pushed as a release.

**Current decision: NOT READY.** Spatial work is on the local `work/spatial-room-workspace` branch. Do not publish it, replace the preview APK, merge the earlier PR as a finished product, or describe the interface as fully verified while the device/account gates remain open.

| Requirement | Implementation / offline evidence | Remaining release gate |
| --- | --- | --- |
| Quest 3 immersive passthrough | Meta Spatial SDK activity, manifest and passthrough enablement; build/lint checks | Launch on the actual Quest 3 and verify passthrough while all panels render |
| Hands and controllers | SDK interaction module, grabbable panels, native UI inputs | Confirm hand ray/pinch, scroll, grab/release, keyboard focus and lock behavior without conflicting gestures |
| Flexible placement | Front/left/right/lower, look-to-surface raycast, resize, follow, lock/unlock and recall | Verify orientation, comfortable depth, wall/table alignment, follow behavior and recovery of out-of-reach panels |
| Room-bound persistence | MRUK UUID plus room-relative pose; codec and actual SDK pose-math tests | Place windows, force-stop/relaunch, recenter/restart the headset, leave/re-enter the room; confirm physical alignment and no other-room restoration |
| Independent windows | Four activity panels, shared WebView account profile, individual URLs/names/options | Confirm separate conversation history/navigation, draft preservation, shared login, hide/show and closing one without affecting another |
| ChatGPT account | Official website, private WebView cookie store, normal sign-in and browser fallback | User sign-in on the device, persistence after restart, chosen OAuth provider compatibility; browser fallback alone does not prove immersive account sign-in |
| Conversation and tools | Official website UI retained; current public composer/dictation/Send selectors supported | Send and receive a real signed-in conversation; exercise the account's required model/tools/apps in the panel |
| Keyboard | Focus bridge plus Android IME/hardware keyboard | System keyboard opening, text editing, submit/cancel and hardware keyboard on Quest |
| STT / voice | Android recognizer insertion and website dictation fallback; microphone request handling | Confirm a speech service or website dictation actually works; approve/deny/revoke mic permissions and check capture stops |
| Camera | Camera2 permissions, private preview stream, YUV_420_888 or JPEG capture; stride/crop packing tests | Verify actual exposed left/right camera IDs, stream configurations, preview/capture orientation, true current images, revocation and lifecycle cleanup |
| Photo upload | Android picker, resizing/orientation handling, website file input; controlled Chromium File/DataTransfer tests | Attach and send real picked/captured photos through the current signed-in ChatGPT page; verify the model sees the intended image |
| Live vision | Configurable periodic images and prompts; tests for draft/busy/upload/stop/navigation/login gates | Confirm real attachments finish before Send, corresponding answers arrive, account limits surface correctly, Stop/hide/background/navigation stop sharing, and no text-only auto-send occurs |
| Performance | Small native shell plus Meta engine; no Unity runtime or local model in spatial module | Four-panel responsiveness, frame timing, memory use, camera/voice coexistence and a sustained headset session |
| Privacy / lifecycle | Capture defaults off, explicit live-session confirmation, bounded sessions, hidden/closed panel stop messages, private receiver controls | Observe actual camera/mic indicators and permission state on close, hide, suspend, sign-out and crash/relaunch |
| Distribution | Build artifacts and debug signature can be checked locally | Stable private signing key and a device-validated install/update path; the development debug key is not a production signing plan |

## Acceptance evidence

Record the headset model, Horizon OS build, app commit/hash, successful and failed steps, and any remaining defects. Automated state/pose/packing tests, a controlled-page browser test, and a logged-out public-page check have different scopes; none substitutes for signed-in or physical-device checks.

The current workspace has no authorized ADB device. The user confirmed that the headset is not connected. No authenticated ChatGPT account is available inside the test browser. These are explicit blockers to end-to-end certification, not passing test results.
