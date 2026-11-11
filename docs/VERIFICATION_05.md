# Luma 0.5 verification

- Both Android apps: debug assembly and Android lint passed.
- Spatial JVM suite: 22 tests passed; adapter suite: 18 tests passed.
- Controlled Chromium: draft protection, attachments, live single submission, Luma role review on first Send, second Send, accepted-briefing deduplication, Enter interception and adapter reinjection passed. No real ChatGPT message was sent by these fixtures.
- Widget Chromium: existing template and network sandbox checks passed against the generated Java document.
- Package ID stays `com.metamanning.spatial`; version code 5, name 0.5.0-dev, launcher label Luma. Same debug signing certificate as 0.4; bundled role asset verified.

New role tests cover preserved requests, no hidden manual submission, disabling/manual attachment, changed preferences and role inclusion in authorized sampled-live questions.

Physical headset and signed-in ChatGPT acceptance remain pending. See [release gates](QUEST_RELEASE_GATES.md). The role is a visible user-provided briefing, not a privileged system prompt.
