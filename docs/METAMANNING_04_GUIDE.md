# Metamanning Spatial 0.4 — browser, widgets and assistant tools

This is an installable Quest 3 **development build**. Automated checks cover the implemented logic and browser renderer. Final headset/account acceptance remains open; see [must-have coverage](MUST_HAVE_COVERAGE.md).

## First use

1. Install the latest spatial APK through Meta Quest Developer Hub, launch **Metamanning Spatial — development**, allow Spatial Data access, and complete **Set up room** if needed.
2. In the workspace menu, open **Assistant**, **Browser**, or **HTML widget**. A recognized mapped room is required for these room-bound panels.
3. Sign into the official ChatGPT page in an Assistant. No OpenAI API key or separate AI backend is required for this account mode.
4. Use **Place**, **Size**, **Rename**, **Focus**, and **Hide/Show** on each window. Use **Save room** for the room layout, and **Recall all** to bring every window back into a readable arrangement.

## Browser

Open **Browser** from the workspace or the Assistant toolbar. Enter an HTTPS website or search phrase. It supports Back/Forward, Reload, bookmarks, finding text, pinch/keyboard zoom through WebView, and user-selected file uploads.

**Ask AI** previews up to 12,000 characters of visible page text with its source. Confirm to offer that excerpt to an existing Assistant window. The assistant protects any existing draft; if it cannot insert the new text, it offers to copy the proposal instead. Nothing is automatically sent to ChatGPT.

The browser has a separate web profile from the Assistant. Website logins are independent. It is a lightweight WebView browser: arbitrary URL schemes and website microphone/headset-camera requests are blocked. Use the Assistant's camera/voice controls for those inputs. Some sites and sign-in providers require a full system browser; the Assistant's **Tools → Open ChatGPT in Quest Browser** remains available.

## Create widgets with AI

1. In an Assistant, choose **Tools → Ask AI to build a widget**.
2. Describe the widget. A draft asks for self-contained HTML/CSS/JavaScript with a responsive dark design and large controls.
3. Review and send the draft yourself. Wait for the reply to finish.
4. Choose **Tools → Create widget from latest AI reply**. Confirm to open the extracted HTML in an offline widget panel.
5. Use **Edit** to change its code, **Options** to import/export HTML or reset its local data, and the workspace controls to position it.

If the ChatGPT page changes and extraction fails, create a blank widget and paste the HTML through **Edit**, or import a `.html` file. The HTML editor and renderer do not depend on ChatGPT DOM selectors.

## Templates and state

The built-in templates are **Clock, Notes, Checklist, Focus Timer, and Calculator**. Notes and checklists save locally through a small per-widget state API. Timers persist their end time and continue by wall-clock time while hidden.

```js
const state = window.metamanning.loadState();
window.metamanning.saveState({ count: (state.count || 0) + 1 });
```

State must be a JSON object of at most 32 KB, with bounded nesting. Save promptly after user edits. Each widget can access only its own state through this bridge; it has no assistant cookies, account session, camera, microphone, filesystem API, or workspace command bridge. Native state persistence still needs device validation.

Widgets run inside an opaque sandboxed iframe in a separate process/profile. Inline scripts, CSS and local form handlers work. Top-level navigation, popups, direct file/content access, and network form submission are unavailable. Networking starts off; **Options → Allow HTTPS network access** enables HTTPS scripts/styles/images and connections. Remote fetches still follow the website's CORS rules. Imported room layouts reset widget network permissions to off. Hidden/inactive widgets block new network loads until shown again.

## Room persistence and recovery

Up to six panels of any mix can be open. **Workspace options** lets you lower the creation limit and toggle passthrough. New windows spread around your view; **Recall all** arranges them by their actual width. **Focus** shows just one window; **Recall all** shows all windows again.

Room layouts retain window type, title, URL or HTML code, placement, size, visibility, lock and widget network preference. Legacy 0.3 layouts migrate as Assistant windows without changing their IDs or positions. Positions remain relative to the recognized MRUK room UUID, not an arbitrary tracking origin.

**Closed windows** retains up to six recently closed windows per room. Reopening a widget keeps its ID, so its local state remains associated with it. Room layout export/import includes HTML code and URLs, but not browser/assistant cookies or widgets' private local state. A layout file can contain private URLs or code; treat backups accordingly.

## Inputs retained

Assistant windows keep **Keyboard, Speak, Photo, Camera, Snapshot, Live**, and the per-window conversation options. Live vision sends periodic image messages with a configurable question, interval and session cap. It is not continuous native ChatGPT video. Dictation and manual photos are reviewed before sending; sampled sharing requires an explicit start and stops on hide/background/navigation.

## Verification

Use JDK 17 / Gradle 8.13 / Android SDK 35:

```sh
gradle -p quest-lite :spatial:assembleDebug :spatial:testDebugUnitTest :spatial:lintDebug :app:assembleDebug :app:lintDebug
npm ci --prefix quest-lite
npm --prefix quest-lite test
npm --prefix quest-lite run test:browser
npm --prefix quest-lite run test:widgets
npm --prefix quest-lite run test:public
```

The widget check uses the actual Java HTML renderer in Chromium and checks all five templates, sandbox isolation, offline blocking, and HTTPS loading against a pinned public CDN script. The public ChatGPT probe uses a logged-out profile and submits no message or image. These are different from native Quest and signed-in account tests.
