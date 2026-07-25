# Handy Voice - Android App

Voice-controlled Android app for The Handy device, designed for users with disabilities.

## Status

🗄️ **Frozen** — superseded by [`../../android-v2/`](../../android-v2/), which recognizes commands by matching against the user's own prerecorded voice samples (MFCC + DTW) instead of running ASR. Nothing here is built or shipped; it is kept for reference.

The sections below (particularly [Voice engine architecture](#voice-engine-architecture)) were moved out of the repo-root `CLAUDE.md` when that file was narrowed to the active app. They are the only detailed record of how this app's Vosk path worked. Treat everything as a description of the frozen code, not as guidance for new work.

> **Historical note**: the parts of this README below "Setup" describe an earlier state of the app, before the Vosk engine and the V2 API client landed. They have not been re-verified.

## Features

- **Continuous Voice Listening** - Phone listens for specific commands
- **Accessible UI** - Large buttons, high contrast, screen reader support
- **Voice Feedback** - Text-to-speech confirmation of actions
- **Emergency Stop** - Always-visible large stop button

## Voice Commands

| Command | Examples |
|---------|----------|
| Start device | "Start", "Go", "Begin" |
| Stop device | "Stop", "Halt", "Pause" |
| Increase speed | "Faster", "Speed up" |
| Decrease speed | "Slower", "Slow down" |
| Set specific speed | "Set speed to 50", "Speed fifty" |
| Set stroke length | "Set stroke to 75", "Stroke length 50" |

## Setup

### Requirements
- Android 8.0+ (API 26)
- Handy API Key from [user.handyfeeling.com](https://user.handyfeeling.com)
- Device Connection Key

### Building

1. Open the `android` folder in Android Studio
2. Sync Gradle
3. Build and run on device

### First Run

1. Tap the settings icon (top right)
2. Enter your Handy API Key
3. Enter your Connection Key
4. Tap Save
5. Return to main screen - device will connect automatically

## Usage

1. Tap the large microphone button to start listening
2. Speak commands naturally
3. See real-time transcription of what's heard
4. Get voice + visual confirmation of recognized commands
5. Use the red EMERGENCY STOP button anytime

## Permissions

- **Microphone** - Required for voice recognition
- **Internet** - Required for Handy API communication

## Architecture

```
app/src/main/java/com/handyvoice/app/
├── HandyVoiceApp.kt          # Application class
├── MainActivity.kt           # Main voice control screen
├── SettingsActivity.kt       # API credentials settings
├── api/
│   ├── HandyApiClient.kt     # Handy REST API client
│   └── HandyApiService.kt    # Retrofit API interface
├── data/
│   └── PreferencesManager.kt # DataStore preferences
└── voice/
    ├── VoiceCommand.kt       # Command types & parser
    └── VoiceRecognitionService.kt # Speech recognition
```

## Notes

- Continuous listening mode automatically restarts after each utterance
- All network calls use coroutines for async operation

---

## Voice engine architecture

Three interchangeable engines, selected in Settings.

1. **Vosk Offline (default, recommended)** — `VoskVoiceEngine.kt`
   - Constrained JSGF grammar: can ONLY output ~100 predefined phrases
   - Eliminates false positives completely (no "surrounded the top" when you say "slow top")
   - ~40MB model downloaded on first launch, cached in app files
   - Fully offline after download
   - **Best for Owen's voice** — constrained vocabulary compensates for recognition difficulty

2. **Deepgram WebSocket** — `DeepgramVoiceEngine.kt`
   - Cloud-based, requires API key
   - Best raw accuracy, but unconstrained vocabulary
   - Has keyword boosting for command words

3. **Android SpeechRecognizer** — `VoiceRecognitionService.kt`
   - Built-in, free, but unconstrained and poor accuracy for Owen

### Engine selection

- Default: `"vosk"` (in `PreferencesManager.kt`)
- Changed in Settings → radio buttons → Save Voice Settings
- `ControlActivity.initVoiceEngine()` reads the pref and creates the right engine

### Vosk key design decisions

**Muted/unmuted pattern (not create/destroy):**
- `SpeechService` is created ONCE during `initialize()` and runs continuously
- Pressing MIC toggles a `muted` flag — when muted, callbacks are ignored
- This avoids Android AudioRecord warmup issues that caused the first 2-3 mic presses to fail
- `stopListening()` = set `muted = true` (SpeechService keeps running)
- `startListening()` = set `muted = false` (instant, no AudioRecord recreation)

**Partial result timeout (1.2s):**
- Vosk doesn't finalize when you repeat the same word ("faster... faster... faster")
- It keeps it as a partial because the recognizer sees one continuous utterance
- Fix: after 1.2s of a stable partial, auto-promote it to a final result
- This allows sequential "faster" / "slower" commands

**Duplicate detection with timestamp suffix:**
- Kotlin `StateFlow` doesn't emit when the new value equals the old value
- If you say "faster" twice, the finalResult would be "faster" both times → second one ignored
- Fix: `emitFinal()` appends `" " + System.currentTimeMillis()` to force uniqueness
- `ControlActivity` strips this suffix with regex before parsing: `rawTranscript.replace(Regex("\\s+\\d{13,}$"), "")`

### Voice command parser (`VoiceCommand.kt`)

Uses **word-based fuzzy matching** with Levenshtein distance:
- Each word in the transcript is compared to known command words
- Threshold: 40% edit distance ratio
- Checks in priority order: presets → two-word commands → single-word → speed numbers → global min/max
- Best match (lowest combined score) wins

### Vosk grammar phrases

All phrases Vosk can output (anything else → `[unk]`):
- **Presets:** `slow/medium/med/fast` × `top/mid/middle/bot/bottom/full` (24 combos)
- **Basic:** `start`, `stop`, `go`, `begin`, `play`, `halt`, `pause`, `end`
- **Speed:** `faster`, `slower`, `speed up`, `slow down`, `quicker`
- **Speed set:** `speed ten/twenty/.../hundred`
- **Global limits:** `maximum/minimum/max/min` × `ten/twenty/.../hundred`

### Adding a voice command (this app)

1. Add the phrase(s) to the `GRAMMAR` list in `VoskVoiceEngine.kt`
2. Add the `VoiceCommand` subclass in `VoiceCommand.kt`
3. Add parsing logic in `VoiceCommandParser.parse()`
4. Add handling in `ControlActivity.executeCommand()`
5. Add a branch in `MainActivity.handleVoiceCommand()` (keep the `when` exhaustive!)

Contrast with `android-v2/`, where adding a command is a row in `Dispatch.TABLE` plus re-recording — no grammar, no parser, no exhaustive-`when` cascade.

### Gotchas

1. **Android SpeechRecognizer** recognizes the entire English dictionary — terrible for constrained-domain commands. "Slow top" becomes "surrounded the top." This is the observation the whole project is built on.
2. **Vosk with grammar constraints** solves that — it can ONLY output phrases you define.
3. **Don't destroy/recreate AudioRecord** on each mic toggle — Android has warmup issues. Use the muted/unmuted pattern instead. (Note: `android-v2` deliberately does construct/release per capture; its `AudioCapture` entry points are short-lived and it has not hit the same warmup problem.)
4. **Vosk doesn't finalize repeated words** — the same word said twice stays a partial. Use a timeout to auto-promote.
5. **`StateFlow` ignores duplicate values** — append a timestamp to force emission of repeated commands.
6. **`when` expressions on sealed classes must be exhaustive** — adding a `VoiceCommand` subclass means updating ALL `when` blocks in both `ControlActivity.kt` and `MainActivity.kt` or it won't compile.
7. **`onResume` reinitializes the voice engine** — intentional, so settings changes take effect, but it destroys and recreates the engine.
