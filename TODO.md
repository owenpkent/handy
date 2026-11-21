# TODO

## Completed

- [x] Build V2 web app with preset grid
- [x] Add stroke position sliders (min/max)
- [x] Add safety limit feature (global min/max)
- [x] Add speed multiplier control
- [x] Add debug panel with live API logging (web)
- [x] Organize project folder structure
- [x] Document API findings (see docs/HANDY_API_NOTES.md)
- [x] Research Handy API v3 capabilities
- [x] Build V1 web app with voice control
- [x] Implement Deepgram voice recognition (web)
- [x] Android app structure + V2 API integration
- [x] Android preset grid (4×3: Top/Mid/Bot/Full × Slow/Med/Fast)
- [x] Android Vosk offline voice engine with constrained grammar
- [x] Android voice commands: start/stop/faster/slower/speed/presets/global min/max
- [x] Android debug panel with Copy button
- [x] Fix V2 stroke range API (single PUT /slide, not separate min/max)
- [x] Fix operation order (stroke → start → speed)
- [x] Fix Vosk mic warmup (muted/unmuted pattern)
- [x] Fix sequential faster/slower (partial timeout auto-promotion)
- [x] Fix global slider ranges (0-100, matching web app)
- [x] Android settings: Vosk/Android STT/Deepgram voice engine selection
- [x] Update LLM onboarding documentation
- [x] **Android v2 scaffold** — new app using prerecorded voice samples (MFCC + DTW); see `android-v2/`
- [x] Android v2: hand-rolled MFCC (radix-2 FFT, mel filterbank, DCT) with JVM unit tests
- [x] Android v2: Sakoe-Chiba banded DTW with path-length normalization
- [x] Android v2: TemplateEngine interface + MfccDtwEngine + TemplateStore (JSON, format-versioned)
- [x] Android v2: Euphonia-style TrainingActivity (3 takes per command, save-as-you-go)
- [x] Android v2: ControlActivity with mic-button and always-on (VAD-gated) modes
- [x] Android v2: SettingsActivity (connection key, listening mode, global max speed, threshold, retrain)
- [x] Android v2: Dispatch table modelled on `vega/commands.py` (originally 9 labels: start/stop/faster/slower + 5 pattern stand-ins)
- [x] Android v2: thorough documentation (README, ARCHITECTURE, VOICE_ENGINE, COMMANDS, TRAINING, TROUBLESHOOTING)
- [x] **Reorganize repo around android-v2** — moved android (v1), v2-web, v1-web, alexa, homeassistant into `legacy/`; android-v2 is the only active app
- [x] **Android v2: Picture-in-Picture control bar** — float into a PiP window with slower / stop / faster; auto-floats on Home while running
- [x] **Android v2: stop winds down** — `rampStop()` eases velocity to 0 over a fixed ~1.5 s before `hamp/stop`, on every stop path (voice, tap, PiP, idle auto-stop, Manual STOP)
- [x] **Android v2: humanized regular motion** — `HumanizeEngine` drifts speed (layered sines + random walk) and stroke depth around the base so steady motion isn't robotic; strength slider in Settings (0 = off)
- [x] **Android v2: reject/noise class** — record the device's own motor sound (Settings → "Record device sound to ignore") into a non-command class so it stops false-firing "stop"
- [x] **Android v2: shared signing keystore** — committed debug keystore (`app/signing/handy.keystore`) so every build shares one signature; in-app updates install over the top from any machine with no uninstall (first in v0.17). See `docs/SIGNING.md`.
- [x] **Android v2: pattern engine port** — `dispatch/PatternEngine.kt`, 12 looping envelopes ported from `legacy/homeassistant/.../patterns.py`. `VoiceAction.Pattern(name, intensity)` replaced the Preset stand-ins; all 12 are tap buttons on the Manual tab.
- [x] **Android v2: stroke-range cap** — global stroke band (min + max) wired into Settings, `PreferencesManager`, and `Dispatch.dispatch()`; preset and pattern ranges are squashed into it. Invert-stroke switch alongside.
- [x] **Android v2: per-command retrain** — Settings → "Re-record voice samples" opens Training without wiping saved takes, and Prev/Next/Skip let the user land on one command. `TrainingActivity` also accepts `EXTRA_LABELS` to scope a session to a subset (used for the reject/noise class).
- [x] **Android v2: trim the voice vocabulary to four labels** (v0.19) — dropped `random` / `deeper` / `shallow`, leaving begin / stop / faster / slower. Depth moved to the Manual stroke sliders; the PiP bar became a fixed Slower / Stop / Faster trio.
- [x] **Android v2: mic off stops the device** (v0.20) - tapping the mic off (tap-mic mode) or pausing always-on listening also stops the device, so motion never outlives voice control. Fixed the race where a command spoken just before mic-off could cancel the stop's wind-down; the listening lifecycle now lives in ListeningSession, covered by ListeningSessionTest + MicOffStopRegressionTest.
- [x] **Android v2: one-button home screen** (v0.21) - the app opens on one big Start / Stop listening button (status + "Heard:" readout); the old full UI (Voice / Manual tabs, connection key, Settings) is behind "More controls". Two-row More header so the gear survives large fonts; button and label shrink for split-screen. `ConnectGuard` stops a double-tapped Connect from firing two connects (`ConnectGuardTest`).
- [x] **Wearable device: research** (2026-11-21) - mechanisms, prior art, catheter and skin safety constraints, electronics, app integration plan. [docs/WEARABLE_DEVICE_RESEARCH.md](docs/WEARABLE_DEVICE_RESEARCH.md)
- [x] **Wearable device: CAD v5** (2026-11-21) - nl-cad assembly in [hardware/wearable-sleeve/](hardware/wearable-sleeve/): open C-shell sleeve with 5 LRAs, chair box with one DRV2605L per motor, TCA9548A mux, STOP button. 32 checks pass.

## Next Up

- [ ] **Wearable: ask the continence nurse / urologist** about over-the-sheath use versus sheath-off sessions, and pressure limits, before anything is worn
- [ ] **Wearable: place the motor sample order** (DigiKey + Adafruit carts are filled) and add the v5 box parts: 4 more DRV2605L, TCA9548A, Adafruit 559 STOP button, STEMMA QT cables, 1S LiPo, PG7 gland
- [ ] **Wearable: bench firmware** - XIAO ESP32-C3 BLE GATT, per-motor DRV2605L via the mux, link-loss watchdog, on-device session cap; sweep frequency and strength per motor for a feel test (check DRV2605L at 100 Hz and 70 Hz)
- [ ] **Wearable: STOP button wiring** - firmware interrupt plus a hardware cut of motor power
- [ ] **Wearable: app side** - extract a `HapticDevice` interface from `HandyV2ApiClient`, add `BleWearableClient` and a device picker; the Handy path stays unchanged
- [ ] **Wearable: soft pneumatics (phase 2)** - pump, normally-open vent, MPRLS, low-set relief valve (part not yet found)

- [ ] **Android v2: validate Picture-in-Picture on Owen's phone** — confirm the per-app PiP permission is on and verify auto-float-on-Home and each of the three controls

- [ ] **Home Assistant integration** — primary path forward for hands-free voice control. See [docs/HOME_ASSISTANT_GUIDE.md](docs/HOME_ASSISTANT_GUIDE.md). Stages:
  - [ ] Stand up HA + Whisper + Piper + openWakeWord + satellite stack via Docker Compose on the Beelink GTR5 (Ubuntu 24)
  - [x] Build `custom_components/handy/` v0.1 — fan entity (start/stop + speed%), services `set_range`/`preset`/`faster`/`slower`, UI config flow with connection-key validation
  - [ ] Deploy integration to HA host (`~/handy-ha/config/custom_components/handy/`) and validate via Developer Tools → Services
  - [ ] Pair USB mic + satellite container; configure ALSA device passthrough
  - [ ] Author custom intents YAML (`config/custom_sentences/en/handy.yaml`) for natural commands
  - [ ] v0.2: add global min/max + speed-limit safety caps as services or as part of config options
  - [ ] v0.3: sensation presets (vibrate, pulse, tease, deep, intense, edge, gentle, jackhammer)
- [ ] **HSSP Integration** — Play funscripts from URLs
- [ ] **Custom Patterns** — Host looping funscripts for HRPP-like behavior
- [ ] **Android UX Polish** — Better connection flow, error recovery, onboarding
- [ ] **Android v2: on-device validation** — record real templates on Owen's phone, walk every command twice, tune `match_threshold` and `Vad.energyThreshold` defaults from observed score distributions
- [ ] **Android v2: preset grid UI parity with v1** — 4×3 slow/med/fast × top/mid/bot/full + sliders. Voice-first stays primary; grid is touch fallback.
- [ ] **Android v2: TFLite embedding engine** — extract EfficientWord-Net's Resnet50 model, port preprocessing to Kotlin, store one average embedding per label. Cosine similarity threshold. Same training UX.
- [ ] **Android v2: wake-word label** — record one template called `wake`; always-on uses the matcher itself as the wake stage instead of VAD. Closer to vega's design.
- [ ] **Android v2: foreground service for true background listening** — `MediaProjection`-style with persistent notification. Optional, behind a setting; useful for hands-free across screen-off.

## Shelved / Abandoned

- [~] **Alexa custom skill** — Lambda + Handy API verified working end-to-end (Manual JSON LaunchRequest succeeds). Voice invocation fails identically on simulator AND real Echo with *"X is not supported on this device"* despite all settings verified correct (Test stage = Development, locale match, account match, Distribution fields filled, multiple invocation names tried). Strongly suspect Amazon content-filter is silently blocking voice routing for adult-themed skills. Pivoting to Home Assistant which has no such filter. Skill ID kept for reference: `amzn1.ask.skill.08af7a77-4701-4292-b027-3840509fcabd`. Lambda kept deployed in case revisiting.
- [~] **Voice Commands (V2 Web)** — Web Speech API requires button press to start and tab focus, which fails the accessibility use case. Subsumed by Home Assistant voice pipeline above.

## Future Enhancements

- [ ] Pattern/routine presets ("slow start", "gradual increase", etc.)
- [ ] Voice-activated timers
- [ ] HSSP funscript support
- [ ] Android settings persistence for global limits and speed multiplier
- [ ] Share Android v2's template-matching engine as a standalone library (used by `android-v2/` here and any future Pi-without-Python rewrites)
- [ ] Cross-device template sync between two devices used by the same speaker (e.g. phone + tablet) — currently template files are per-install
