# Luma spatial second brain — capability sourcing and integration plan

Status: native library, gestures, environments, and local share gates are implemented; **not Quest-device validated**. Preserve the existing Meta Spatial SDK 0.15 Android application and its account-based ChatGPT panels. Do not swap in a Unity host or import whole Unity applications.

## Experience principles

- **Thoughts are manipulable objects.** Notes, references, concepts, conversations, models and tasks have stable IDs, provenance, relationships and spatial views. A panel is only one representation of an object.
- **Playful, not gimmicky.** Pull a distant note to you, toss a thought into a cluster, pin an idea to a wall, fan out related concepts, collapse a graph into a single card, and summon a project constellation.
- **Sitting-first ergonomics.** All actions work with head-ray/controller/menu fallback. Keep reading objects at comfortable depth, provide recenter/recall, avoid rapid forced motion and require deliberate selection for destructive actions.
- **One coherent application.** Shared interaction semantics, local-first storage, scoped permissions and a performance budget; no separate islands of incompatible interfaces.
- **High-quality worlds only.** Choose a few curated environments with strong art direction, comfort and performance, rather than low-quality asset packs. Passthrough remains the default.

## Current first increment

The native spatial workspace now offers **Pull here** per panel and **Pull this window to me** under Place. It moves the *existing* panel to ~1.05 m in front of the seated user, unhides/unlocks it, and persists the pose if a room is recognized. It refuses to move an actively grabbed panel. This is a menu/controller-accessible precursor to the requested Spider-Man-style hand gesture, **not** hand-ray gesture recognition, a web visual, or physics animation. Physical headset acceptance remains open.

## Source candidates — review, don't blindly vendor

| Source | Capability | Reuse approach | License / caveat |
|---|---|---|---|
| [JarvisVR](https://github.com/sumitaich1998/jarvisvr) | Holographic widgets, voice and agent protocol | Already imported; preserve attribution, adapt schemas to Luma widgets | MIT; real AI backend optional, mock offline |
| [Meta Spatial SDK](https://developers.meta.com/horizon/documentation/spatial-sdk/) | Native Quest passthrough, spatial panels, hand interaction, MRUK | **Existing host**, extend native systems | SDK-specific terms; not interchangeable with Unity |
| [Unity First Hand](https://github.com/oculus-samples/Unity-FirstHand) | High-quality hand manipulation patterns | Study interaction design; implement equivalent native gestures | MIT repository, Oculus SDK separate license |
| [Unity The World Beyond](https://github.com/oculus-samples/Unity-TheWorldBeyond) | Scene-aware playful MR, portals and environmental transitions | Reference for curated room transitions and affordances | Multiple licenses; Unity sample |
| [Unity North Star](https://github.com/oculus-samples/Unity-NorthStar) | High-end Quest visual fidelity and interaction | Art/performance reference only; do not import game assets blindly | MIT repo, SDK/asset rights need review |
| [Graph2VR](https://github.com/molgenis/Graph2VR) | Linked-data exploration and graph visualization | Translate graph data model and interactions; do not paste Unity code into Android host | LGPL-3.0; review obligations |
| [Meta Shared Spatial Anchors](https://github.com/oculus-samples/Unity-SharedSpatialAnchors) | Co-located shared holograms | Study anchoring and synchronization; use native-compatible APIs | Unity sample, not a drop-in |
| [Meta Passthrough Camera Samples](https://github.com/oculus-samples/Unity-PassthroughCameraApiSamples) | Camera and real-world context | Study privacy/vision pipelines; current Camera2 work is separate | Unity sample, runtime permission and OS gates |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | Local inference experiments | Optional backend adapter or carefully benchmarked on-device model | MIT; RAM, thermals, battery constraints |
| [Godot](https://github.com/godotengine/godot) | Alternative open 3D engine | Research only, not an extra runtime in Luma | MIT; architecture mismatch |

No external source has been copied by this branch. Source list is a candidate manifest, not a completed license audit.

## Proposed interactions and quality gates

| Interaction | Experience | First implementation | Acceptance |
|---|---|---|---|
| Web-sling thought | Aim at a distant note, pinch and draw it toward you with a tether | Existing **Pull here** fallback; later SDK hand-ray + visible tether | Must never activate during ordinary scroll or grab; cancelable |
| Thought toss | Flick a note toward a named cluster or board | Deterministic target preview, then commit | Undo, no accidental deletion |
| Constellation | Expand one idea into linked concepts in 3D | Local graph store + spatial layout | Stable IDs, search, evidence links, bounded node count |
| Thought portal | Walk or select between distinct thinking environments | Passthrough + curated environment presets | Comfort, high fidelity, Quest frame budget |
| Memory palace | Anchor notes to recognized physical room locations | Extend existing MRUK room persistence | Relocalization and room privacy checks |
| Focus bubble | Dim unrelated content and gather relevant cards | Reversible visibility filter | One-action exit, never lose work |
| Time scrub | Explore how a project or idea changed | Event log with provenance and undo | History accurately reflects stored events |
| Co-think | Share selected nodes with a second Quest | Explicit session permissions and sync | No automatic sharing of private conversations |
| Look-and-ask | Select a real object and ask AI for contextual explanation | Opt-in snapshot pipeline | Clear capture indicators, explicit submission |
| Build bench | Pull diagrams, docs and code into a spatial workbench | Reuse existing browser/widget panels | Offline widgets and secure browser separation |

## Architecture boundaries

1. Retain `quest-lite/spatial` as the production-target host. Existing `unity-client` remains a reference implementation, not a runtime dependency.
2. Introduce a stable `Thought` entity with ID, type, content reference, links, provenance, privacy and room-independent coordinates; separate storage from visible panels.
3. Add a small gesture state machine: idle -> aiming -> selected -> preview -> commit/cancel. Controller and menu parity first. Use head/hand ray only after headset tests.
4. Environments are downloadable, licensed and curated packages with LOD, lighting, occlusion and frame-time gates. No uncontrolled environment marketplace in the first release.
5. Sync only user-approved objects, not camera streams, ChatGPT cookies or entire app state. Explicit host/guest roles.
6. Never claim that the ChatGPT website grants the native shell programmatic access to assistant tools. Native actions require a separate reviewed bridge.

## Immediate next implementation slices

- **A.** Verify Pull here on Quest 3 (hands, controllers, room save/reload and comfort).
- **B.** Local Thought entity + CRUD + link store, independent of window limit.
- **C.** Spatial note cards and graph layout, with search and retrieval.
- **D.** Hand-ray selection and spring/tether animation, cancel and undo.
- **E.** Two curated thought environments, one calm dark observatory and one bright design studio, with passthrough fallback.
- **F.** Optional shared session and AI retrieval after security/performance gates.

## Release honesty

Existing automated build checks are not physical Quest validation. This branch has not been compiled or tested on a headset. No external repositories were bulk imported. Keep it in a draft PR until native build, unit tests, headset acceptance, performance and license checks pass.

## Implementation recorded on this line

The native host is still `quest-lite/spatial`. Thoughts live in a local library with stable IDs, types, content references, links, provenance, privacy, and room-independent coordinates. Window panels stay capped at six; the library does not. Note cards are a bounded view of that library.

- **Web-sling.** Menu **Pull here** still moves an existing panel to 1.05 m, unlocks it, and refuses a grabbed panel. Thought sling walks idle → aiming → selected → preview, shows a tether, and commits or cancels. Scrolling and grabbing do not complete it. Head and hand rays stay off until a headset test.
- **Toss, constellation, focus, history, palace.** Named clusters preview a landing and do not delete. Fan-out is capped, searchable, and undoable. Focus dims other cards and windows and one action restores them. The event log is what time scrub reads. Room anchors restore only in the recognized room.
- **Environments.** Passthrough, Calm observatory, and Bright design studio are bundled presets with licenses and a frame-time gate. There is no environment marketplace and no imported mesh pack. A missed frame budget returns to passthrough.
- **Co-think, look-and-ask, retrieval, bench.** Sharing sends only host-approved, shareable thoughts. Conversations, camera references, and cookies are refused. Look-and-ask is opt-in, shows a capture indicator, and submits only as a reviewed draft. Retrieval does the same. The bench reuses browser and offline widget panels.

Still blocked without a Quest: physical Pull here acceptance (hands, controllers, room save/reload, comfort), real head/hand-ray pinch, frame timing on device, and a second headset for co-think transport. The share payload is local; it is not a network session.
