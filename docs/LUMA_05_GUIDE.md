# Luma — Your meta assistant

Version 0.5.0-dev renames the native app to Luma. The package remains `com.metamanning.spatial`, preserving the upgrade path, account profiles and room data. The repository name remains Metamanning. Use the [0.4 workspace guide](METAMANNING_04_GUIDE.md) for spatial windows, browser and widgets; launch the app as **Luma**.

## Assistant role

The **Role** button opens the full bundled briefing, an enable toggle, and optional preferences stored per assistant window. It describes Luma's identity, the spatial workspace, browser context, snapshot vision and HTML widget format. It tells the model to avoid claiming native actions or continuous perception it does not have.

With the default role enabled, the first Send or Enter in a conversation without the current briefing prepares a visible role-bearing draft and pauses submission. Review it and press Send again. Your text and attachments remain in place. The app recognizes the current briefing in rendered user messages and avoids adding it on every turn. A new conversation or changed role may need a new briefing; virtualized history can cause a repeat. **Attach to draft** also works manually, including with automatic briefing disabled.

This is a user message within ChatGPT's instruction hierarchy, not a replacement system prompt, global custom instruction setting, or guarantee of model compliance. It is only available to the model after the message is sent. Authorized sampled-live camera questions include the briefing when needed in the same snapshot message; they follow the existing live-sharing controls.

## Widgets and compatibility

New generated widgets use `window.luma.loadState()` and `window.luma.saveState(object)`. Existing `window.metamanning` widgets continue to work. Both names refer to the same bounded widget state interface. Native actions remain user-operated; the assistant has no hidden headset control bridge.

## Verification limits

Build, lint, unit and controlled Chromium checks pass. Actual Quest 3 passthrough, hand input, room relocalization, signed-in ChatGPT submission, speech and camera acceptance are still required. This is a development APK.
