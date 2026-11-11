# Must-have coverage — 0.4 development build

**Status: implemented flows with partial verification; not certified as a finished interface.** No authorized Quest is connected to this workspace, and no account is signed into the test browser. A passing build or browser fixture does not verify physical headset behavior.

| Your requirement | Implemented path | Evidence and remaining work |
| --- | --- | --- |
| Quest 3 passthrough | Immersive Meta Spatial SDK activity; passthrough toggle | Builds/manifest checks pass; actual headset rendering remains unverified |
| Hand controls | SDK interaction module, grabbable panels, large native controls | Actual hand rays, pinch, scroll, grab, focus and virtual keyboard must be tested on the Quest |
| Your ChatGPT account | Official website in Assistant panels, shared assistant profile, no API key | Logged-out page compatibility checked; embedded sign-in, account tools and conversation behavior still require your account on the headset |
| Conversation | Official ChatGPT UI and per-window URLs | Draft protection and current public composer verified; real signed-in send/receive and tools remain open |
| STT | Android recognition intent and website dictation fallback | Transcript insertion tested; the available Quest speech service/microphone path is unverified |
| Keyboard | Native focus/IME request and hardware input | Browser composer events tested; actual Quest keyboard needs testing |
| Camera vision | Camera2 preview, YUV/JPEG conversion, snapshot attachment | YUV stride/crop tests pass; real camera formats, orientation and model receipt require the Quest and signed-in ChatGPT |
| Live vision | Explicit, configurable periodic image-and-prompt messages | Draft/busy/upload/navigation/stop behavior tested in fixtures; actual signed-in image submission remains open. Continuous native video is not implemented |
| Photo upload | Android picker, orientation correction, website file input | Real browser File/DataTransfer tested on a controlled page; actual picker/account upload/model result unverified |
| Spatial placement options | Presets, surface raycast, resize, lock, follow, focus, hide/show and recall | Actual SDK pose math tested; hands, comfortable placement and surface alignment remain hardware tests |
| Room-bound persistent windows | MRUK room UUID and relative pose; import/export; closed-window recovery | Serialization, legacy migration and pose math tested; restart/recenter/relocalization in the physical room remains open |
| Browser inside the app | Private Browser activity/profile with navigation, bookmarks, find and page-to-assistant drafts | Native implementation builds and URL policy tests pass; actual spatial browser rendering and website compatibility need headset testing |
| HTML widgets that display | Widget activity, editor/import/export, templates, isolated renderer and local state API | Actual renderer and five interactive templates pass in Chromium; the native bridge, spatial display and native persistence remain device gates |
| More assistant tooling | Widget prompt drafts, latest-reply HTML extraction, browser context handoff | Adapter extraction and draft-preservation tests pass; actual signed-in DOM and cross-panel handoff require device/account testing |

The full acceptance sequence is in [QUEST_RELEASE_GATES.md](QUEST_RELEASE_GATES.md). The installation steps and new workflows are in [METAMANNING_04_GUIDE.md](METAMANNING_04_GUIDE.md).

## Luma 0.5 identity update

The launcher and native shell are Luma — Your meta assistant. A configurable per-window role briefing is prepared for review in conversations; it defines identity and available capabilities without claiming native tool access. Controlled role tests pass; real signed-in submission remains a device/account acceptance gate. See [Luma guide](LUMA_05_GUIDE.md).
