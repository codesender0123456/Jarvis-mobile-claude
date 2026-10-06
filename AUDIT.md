# JARVIS Mobile: audit and fix log

Status: **F** fixed, **P** partly fixed (limit stated), **N** not done (reason stated).
Nothing here has been compiled or run on a device; the first CI run is the real test.

## Build, Gradle and CI
| # | Problem | Status | What changed |
|---|---|---|---|
| 1 | `AudioEngine.kt` declared `private const val` inside the class body: a hard compile error, so the project could not build | F | Engine rewritten; constants live in a companion object |
| 2 | `SafetyGuard.kt` used `CancellationException` without importing it | F | Import added |
| 3 | No Gradle wrapper (`gradlew`, `gradlew.bat`, wrapper jar) | F | Added from the official Gradle 9.3.1 sources; CI validates the jar |
| 4 | Release signing config always created, referencing a missing keystore and null passwords | F | Created only when keystore and all three secrets exist; otherwise release builds unsigned |
| 5 | `-Xmx4g`, parallel builds, 4 workers: heavy for low-RAM machines | F | `-Xmx2g`, metaspace cap, no parallel, 2 workers |
| 6 | Unused dependencies (Retrofit, Moshi + codegen KSP, Coil, OkHttp logging, CameraX view, Media3 UI) | F | Removed. Unused catalog entries (Firebase, Play Services, Credentials, Accompanist) are not referenced by any module |
| 7 | OkHttp 4.10.0 (2022) for a security-sensitive socket client | F | 4.12.0 |
| 8 | AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.2.10 + KSP 2.3.5 + Compose BOM 2024.09.00 (old BOM next to new AndroidX) | P | Not changed: I cannot verify compatibility without building. CI will show it. BOM is the first thing to bump if Compose errors appear |
| 9 | `.gitignore` lacked keystores and `.env.*` | F | Added |
| 10 | API key in build config | F (already) | No BuildConfig field, no secrets plugin. Key lives only in encrypted prefs; it is redacted from logs and error text |

## Gemini Live protocol
| # | Problem | Status |
|---|---|---|
| 11 | Session resumption: wrong field, handle never read from `sessionResumptionUpdate` | F: `sessionResumption{handle}`, updates stored encrypted, stale handle dropped |
| 12 | Binary frames ignored | F |
| 13 | Audio streaming started in `onOpen`, before `setupComplete` | F: starts on `setupComplete`; frames dropped while not ready |
| 14 | Deprecated `mediaChunks` audio payload | F: `realtimeInput.audio` with `audio/pcm;rate=16000` |
| 15 | No context-window compression | F: sliding window |
| 16 | Model ladder stepped on any error, never recovered, used an invented model id | F: `LiveModelLadder`, primary + one fallback, steps only on quota (5 min) or lost access (6 h), self-recovers. Both ids confirmed in Google's current docs |
| 17 | Reconnect missing on `onClosing`/`onClosed`, no backoff, stopped at the last rung | F: all close paths, 1-30 s jittered backoff, stale-socket callbacks ignored |
| 18 | Tool responses could be sent on a different socket than the call | F: dropped if the session changed |
| 19 | Voice change did not apply live or wiped context | F: `changeVoice()` reconnects with the handle |
| 20 | Unspecific diagnostics | F: close code + redacted reason surfaced in the log and `lastError` |
| 21 | Wire format untestable (mixed into the socket class) | F: `LiveProtocol` is pure and unit-tested |
| 22 | `gemini-3.8-*` ids in the one-shot ladder | F: spec ladder; all five ids appear in Google's model list |

## Audio
| # | Problem | Status |
|---|---|---|
| 23 | Uncontrolled chunk size (whatever the device buffer was) | F: fixed 40 ms / 1280-byte frames (`PcmChunker`) |
| 24 | Echo guard forwarded the assistant's own voice to the model | F: speaker bleed is replaced by silence (`EchoGate`); headset forwards normally |
| 25 | No platform AEC/NS; wrong source priority | F: VOICE_COMMUNICATION first, AEC + NS enabled |
| 26 | Barge-in did not cut buffered speech (`AudioTrack.flush()` is a no-op while playing) | F: pause, flush, play |
| 27 | AudioRecord not released when the loop exited by itself | F: released on every exit path |
| 28 | Input/output device settings saved but never applied; output not selectable | F: `setPreferredDevices` applies live; one list selects both directions |
| 29 | Language setting saved but unused | F: in the prompt; new "Auto" option |
| 30 | Prompt "User Address" showed the stored name or the default "Sir" | F: name vs honorific handled explicitly |

## Wake word, services, boot
| # | Problem | Status |
|---|---|---|
| 31 | Wake word was a volume threshold | P: real phrase recognition (`SpeechRecognizer` + `WakePhraseMatcher`). **Not guaranteed offline** (Android decides; offline preferred). No openWakeWord/Porcupine model is bundled; the detector is a swappable class |
| 32 | Wake only opened the screen, never started an interaction | F: starts a real session |
| 33 | Second, separate microphone pipeline competing with the session | F: one owner at a time; detector stops before the session opens the mic |
| 34 | "Sleep" kept the mic open | F: asleep = microphone released. Consequence: it cannot hear "Hey Jarvis" while asleep; wake by notification, tile or bubble |
| 35 | Overlay never started, static bubble | F: setting + live-state bubble (colour, caption, last line), tap to talk, long-press for HUD, draggable |
| 36 | Tile only opened the app, always "active" | F: toggles the real session, mirrors state, falls back to the HUD if Android refuses |
| 37 | Boot receiver: Android 14+ forbids a mic service from `BOOT_COMPLETED` | F: `BootPolicy`; Android 14+ posts a tap-to-start notification |
| 38 | Briefing always first fired tomorrow; recap was never written so it never had content | F: today at 07:30 if still ahead (unique work, no stacking); recap written at session end |
| 39 | Notifications ignored the runtime permission | F: gated; reminders warn when notifications are off |
| 40 | Sessions died when the app went to the background (no foreground service) | F: sessions are hosted by the microphone service |

## Actions and safety
| # | Problem | Status |
|---|---|---|
| 41 | Sensitive-action check ran after the action's code | F: registry gate runs first; the body runs only after the user's tap |
| 42 | Inconsistent confirmation (calls and SMS inside the action, delete inline) | F: one mechanism via `confirmationFor` |
| 43 | Delete was permanent | F: moves to a trash folder, undoable |
| 44 | Undo missing for DND, reminders, memory; lost on restart | P: added; setting-type undos (volume, brightness, DND, flashlight) survive restart. Rename/create/reminder/memory undos are session-only (they are closures) |
| 45 | Flashlight picked the first camera (often the front, no torch); undo guessed state | F |
| 46 | Agent only wrote a plan | F: plans against real tools, runs steps in order, stops at failure or at a confirmation |
| 47 | Search modes were one DuckDuckGo instant-answer call; claimed HUD display | F: Google Search grounding with distinct prompts per mode, sources, DDG fallback; no display claim |
| 48 | Memory edits not visible to the running session | F: bracketed system notes (and the prompt tells the model not to reply to them) |
| 49 | UI toggle used a state snapshot, duplicating sessions while connecting | F: `isWanted` is the source of truth |

## Vision
| # | Problem | Status |
|---|---|---|
| 50 | `inspect_visual_input` was a stub | F: CameraX frames (front or back) and MediaProjection screen frames at 1 fps, one source at a time, labelled in tool results and notes. Screen sharing needs the Android prompt, shows a notification with Stop, and stops after 10 minutes or at session end |

## Tests
Replaced the tautological `ModelLadderTest` (asserted against its own lists). Added tests for the wire protocol (setup, resumption, audio/video/text frames, tool calls, interruption, close classification), the model ladders, chunking, echo/barge-in, wake phrase, boot policy, the confirmation gate, the agent, and undo persistence.
Not covered, because it needs a device or emulator: real AudioRecord/AudioTrack, CameraX, MediaProjection, SpeechRecognizer, services and the socket itself.

## Still open
- Memory search is SQL `LIKE`, not FTS; the prompt carries up to 30 facts rather than a core + key index.
- The system prompt is a Kotlin string, not an editable asset with tokens.
- Briefing has no weather or news section.
- Output of many tools still says "Sir" regardless of the user name setting.
- WhatsApp/Telegram/email compose open the app; the user presses send there (by design, stated in descriptions).
