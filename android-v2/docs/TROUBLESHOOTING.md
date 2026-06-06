# Troubleshooting

Common problems and concrete fixes. Build issues first, then runtime, then matching pathologies.

## Build issues

### `JAVA_HOME is not set and no 'java' command could be found`

`gradlew.bat` needs a JDK. Set `JAVA_HOME` to your JDK install before running:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Android Studio bundles a JDK (JBR) at `<install>\jbr`. If you have a system JDK (Temurin, Oracle, Zulu), point `JAVA_HOME` there instead. Any JDK 17+ works for launching Gradle; the build itself runs on a JDK 21 toolchain that Gradle auto-provisions from Foojay (see `gradle/gradle-daemon-jvm.properties`).

### `SDK location not found`

Gradle can't find the Android SDK. Fix one of two ways:

**Option A: `local.properties` in the project root.** (Already done in this repo.)

```properties
sdk.dir=C\:\\Users\\Owen\\AppData\\Local\\Android\\Sdk
```

**Option B: `ANDROID_HOME` environment variable.**

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

If you installed the SDK to a different path (Linux/macOS, custom install), adjust accordingly.

### `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`

The `gradle/wrapper/gradle-wrapper.jar` is corrupt or missing. Regenerate it:

```powershell
# Use any installed gradle to run the `wrapper` task in this project:
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
& "C:\Users\<you>\.gradle\wrapper\dists\gradle-9.4.1-bin\<hash>\gradle-9.4.1\bin\gradle.bat" `
    -p . wrapper --gradle-version=9.4.1
```

Or just open the project in Android Studio and let it sync — it regenerates the wrapper as part of normal sync.

### Build is slow on first run

Expected. First-run downloads:

- Gradle 9.4.1 distribution (~150 MB).
- JDK 21 toolchain from Foojay (~190 MB), provisioned per `gradle/gradle-daemon-jvm.properties`.
- Android SDK platform 36 (~70 MB).
- Build tools 36 (~70 MB).
- Maven/Google deps (Retrofit, OkHttp, kotlinx-coroutines, datastore, ~30 MB).

Subsequent builds use the local Gradle cache and are 10–20 s.

### Deprecation warnings about `android.builtInKotlin`, `android.newDsl`, `android.r8.optimizedResourceShrinking`

Cosmetic. These flags in `gradle.properties` are pinned to AGP-9.x defaults (which become the AGP-10 defaults). The warnings will disappear when we move to AGP 10. No action required.

## Mic not picking anything up

The single best diagnostic is the **live peak meter** on the Voice tab (under "Heard:") and on the Training screen (above the record button). When the mic is open, the bar should grow/shrink with your voice. If it stays flat-zero, the mic isn't reaching the app.

### On the Android emulator

The emulator's virtual microphone is fiddly. We use `MediaRecorder.AudioSource.MIC` (not `VOICE_RECOGNITION`) because the emulator routes audio to `MIC` reliably and to `VOICE_RECOGNITION` not at all in many configurations. If you're still getting silence:

1. **Emulator → Extended Controls (⋯) → Microphone:** toggle **"Enable Host Microphone Access"** ON. Some AVD images also need **"Virtual microphone attached"** ON in the bottom Virtual Headset section — counterintuitively, that's the one that tells Android "yes, there's a real mic device" (without it, `AudioRecord` reads silence indefinitely).
2. **Windows-side:** Settings → Privacy & Security → Microphone → "Let desktop apps access your microphone" ON. Also confirm your real mic isn't muted and the input level isn't at 0.
3. **Cold-boot the AVD after toggling.** Device Manager → ⋮ next to the device → "Cold Boot Now". Hot reboots don't re-enumerate audio devices.
4. **Verify with Google Assistant in the emulator.** Long-press the home button and try a voice command. If the assistant can hear you and our app can't, the emulator isn't routing host audio to `MIC` at all. Either fix the emulator (sometimes wiping the AVD and re-creating helps) or move to a real device.

If you can hear yourself when you tap **Play** after a take, the mic *is* working — your problem is elsewhere (threshold, templates, etc.). If Play is silent, the take was silent.

### On a real Android device

1. Mic permission must be granted: Settings → Apps → Smart Dong v2 → Permissions → Microphone → Allow.
2. Don't cover the mic with the phone case or your hand.
3. Mute switch is not on (some devices have a hardware switch that gates `MIC`).
4. The peak meter should respond to voice or finger-tap on the mic position. If it doesn't, something below the app layer is wrong.

## Runtime: app launch

### App launches into a blank "scaffold" screen, no Training screen

You may have an old build. The Phase 1 scaffold's `ControlActivity` was a stub showing "Handy Voice V2 - scaffold" (back when the app was still called Handy Voice V2). The final `ControlActivity` routes to `TrainingActivity` if no templates exist on disk. Reinstall the latest debug APK:

```powershell
.\gradlew.bat :app:installDebug
```

### Training screen, but mic doesn't capture

The first time you tap Record, Android shows a permission prompt. **Deny** sends you to the error path; **Allow** is required.

If you denied:

- Settings → Apps → Smart Dong v2 → Permissions → Microphone → Allow.

Re-launch the app and try again.

### `Microphone permission required` shown under the take counter

Same as above. Permission was denied at the prompt or revoked in system settings.

### Takes record but Play does nothing

Your phone is on silent. `AudioTrack` with `USAGE_MEDIA` respects the media volume slider. Turn it up.

If media volume is up and Play is still silent, the take captured but is silent — your mic is muted or covered. Hold the phone with the mic unobstructed.

## Runtime: connection

### `Device not connected. Make sure your Handy is online.`

Your connection key is valid but the device isn't currently online. The Handy needs to be powered on and connected to your account at handyfeeling.com. Check:

1. Device powered on.
2. Device connected to Wi-Fi (LED indicator).
3. Device shows online at https://user.handyfeeling.com.

The same connection key works in `v2-web/`, so you can A/B test there if the Android client is misbehaving.

### `Connection key required`

The Connect button fires, but the input is empty. Type your connection key (alphanumeric, lowercase, no spaces) into the input field and tap Connect again. The app remembers it between launches.

### `Failed to set mode`

The device accepted the connection check but rejected the HAMP-mode setup call. Usually a firmware issue. Try:

1. Power-cycle the device.
2. Open `v2-web/` and connect there — if `v2-web/` can talk to the device, the device is fine and the issue is here.
3. Open the debug panel (Show Debug at the bottom) and copy the log — it will show the exact HTTP status code from the failed `PUT /mode`.

### Connect spinner stays up indefinitely

Network is hung. The Retrofit client has a 15-second timeout on each request; after that you should see an error. If you never see one, the app may have backgrounded and the coroutine got suspended. Switch to the app and watch the debug panel.

## Runtime: matching

### `No voice samples yet — tap Re-record below.`

The engine loaded zero templates. Either:

- You haven't completed Training yet. Settings → **Re-record voice samples**.
- The templates directory was wiped externally (e.g. you cleared app data). Same fix: Re-record.
- The on-disk takes were saved by an older app version with an incompatible MFCC pipeline (format version is below the current 3). They're auto-filtered out on load and you'll see this message; re-record to repopulate.

### Every command shows `? <label> (score X)` (below threshold)

Your match scores are too high to count. Common causes:

- **You're recording in posture A and using in posture B.** See [TRAINING.md § Speak naturally](TRAINING.md#1-speak-naturally-not-into-the-mic). Re-record from your actual using posture.
- **Threshold is too strict.** Default is 30. Bump it to 40 in Settings and see if matches start to count. With CMN applied, healthy scores are usually 5–15; if you're seeing 30+ consistently your templates and live audio are too different.
- **You used different words at training vs use.** Trained `start` but saying `begin`? It won't match. Train every word you want recognised. (And note: the canonical label is `begin`, not `start` — we renamed it because `start`/`stop` collide phonetically.)

### Match shows `? close call — try again` even though I said the right word

The match passed the threshold but failed the **margin gate**: the runner-up label scored within 5% of the winner, so the engine refused to commit. Causes:

- Two of your labels sound too similar to each other in MFCC space. Either re-record one more crisply, or pick more phonetically distinct labels (the `start`/`stop` lesson applies broadly).
- Only one or two labels are trained — with so few choices, the margin is inherently small. Adding more labels usually helps because each one gives the matcher more vocabulary "shape" to discriminate against.
- The per-label cross-validation gate inside Training is your inline safety net. If you skipped past an outlier-take dialog without re-recording, that label may be the one tripping the margin gate now. Fix: Settings → **Re-record voice samples** → navigate to the offending label → tap the Sage Re-record button to capture a fresh 3-take session for it.

### Wrong command fires every time

Two of your trained labels sound alike to the matcher.

Look at the debug log:

```
Heard: stop  score=24.1 margin=1.8  (below threshold)
```

Margin `1.8` means the second-best label scored `25.9`. They're too close. Three fixes, easiest first:

1. **Re-record the offending label.** Speak more distinctly; emphasise the consonants that differ.
2. **Change the word.** If "stop" and "slow" keep colliding, rename one. See [COMMANDS.md § Adding a new command](COMMANDS.md#adding-a-new-command).
3. **Tighten the threshold AND require a minimum margin.** Phase 1 dispatch only checks `score <= threshold`. You can patch `ControlActivity.handleAudio` to also require `match.margin > 5` and reject low-confidence matches. Future Phase 2 toggle in Settings.

### Always-on misfires on silence / music / TV

Energy + zero-crossing-rate VAD has false positives. Mitigations:

- **Switch to button mode.** Settings → Listening mode → Tap mic to listen.
- **Move the mic away from the noise source.** Surprisingly effective; the VAD trips on amplitude.
- **Raise `Vad`'s `energyThreshold`** if you're comfortable rebuilding. Default is `0.012`; try `0.025` for a noisier room.
- **(Future)** Wake-word label that opens the matcher only after you say a specific word.

### Always-on never fires when I speak

VAD energy threshold is too high for your voice. Two paths:

- Move the phone closer to your mouth.
- Lower `Vad.energyThreshold` in `audio/Vad.kt` (rebuild required). Default `0.012`; try `0.005`.

In Settings, the threshold slider only changes the match threshold, not the VAD threshold. Exposing VAD sensitivity as a setting is a Phase 2 item.

### The phone screen sleeps while the app is open

Fixed. `ControlActivity` (and `SettingsActivity` / `TrainingActivity`) set `FLAG_KEEP_SCREEN_ON` in `onCreate`, so the screen stays on the **whole time the app is in the foreground** — whether or not you're listening. The flag is only honored while the window is visible, so backgrounding the app or navigating away lets the screen sleep normally.

### Listening stops when the phone screen sleeps

Fixed. The always-on screen flag above keeps the screen lit while the app is foreground. Separately, while the listening loop is active `ControlActivity.keepAwake(true)` also holds a `PARTIAL_WAKE_LOCK`, so even if you deliberately press the power button to darken the screen, the CPU won't suspend mid-capture: `onStop` sees a non-interactive screen and **keeps the loop running** on the wake lock — commands still fire with the screen dark. Navigating away (Home / Settings) tears the loop down and drops the lock; coming back re-arms always-on automatically.

If you still see listening die on screen-off, check: (1) the app actually started listening (Always-on mode, or you tapped the mic in button mode — the peak bar should be live); (2) the device isn't force-stopping the app under aggressive OEM battery management (some Android skins ignore wake locks for "optimized" apps — exclude the app from battery optimization in system settings). Note that `android:keepScreenOn` on an `<activity>` in the manifest does nothing; the working flag is set in code.

### Listening stopped and the device stopped after a while of not using it

By design. After the idle window (default 10 min) with no voice command **and** no screen touch, the app stops the Handy and stops listening — it assumes you've walked away. Tap the mic to resume (this works in always-on mode too). The timer resets on every voice match and every touch, so it won't fire while you're actually using either the voice or Manual controls. Change the window in **Settings → Idle auto-stop (minutes)** (1–30 min). Note: the screen still stays on as long as the app is in the foreground (that's the always-on keep-awake) — the idle stop halts the device and frees the mic, it no longer puts the screen to sleep.

### A setting I changed didn't take effect

Fixed. Settings used to apply only when you tapped a **Save** button, and leaving via **← Home** or the back gesture discarded any unsaved changes (so a tweaked global limit, threshold, or stroke band would silently revert). Now every control auto-applies the instant you change it: sliders persist on release, the listening-mode radio and invert switch on toggle, and the connection key on focus-loss (with `onPause` as a backstop). There is no Save button. The value is written immediately and `ControlActivity.onResume` re-reads it and pushes it to the device, so the change is in effect as soon as you return to the control screen.

## Runtime: dispatch

### Device starts at low speed and nothing changes when I say `faster`

Working as intended. The global max speed cap (default 30%) scales everything. `start` sends design speed 25 → device speed 7. `faster` adds design +15 → +4 device points.

If you want a higher ceiling, **Settings → Global max speed**. Owen's default is `30` per his preferences. Push to 50 or higher if you're confident, but the cap exists for a reason — see [README.md § Safety caps](../README.md#safety-caps).

### Device doesn't start when I say `start`

The dispatch path is `start` → `SetSpeed(25)` → ensure running → `setSpeed`. If `api.start()` fails, the speed is never written. Open the debug panel — you'll see `Start failed: <code>`. Causes:

- Device offline (see "Device not connected" above).
- Mode hasn't been set (was supposed to happen on `connect()`; if connect was skipped, mode is wrong). Re-tap Connect.

### `Heard: faster` shows but device doesn't speed up

The matcher fired (good); the dispatch went through (look at the log for `Speed: ...`); the device didn't change. Possibilities:

- The device is already at max within the cap. `faster` clamps at 100 design = `globalMaxSpeed%` device.
- The device is connected but in `HSSP` mode (script playback), not `HAMP`. The Phase 1 `connect()` flow sets HAMP; if you switched modes externally, reconnect.

## Diagnostics

### How to capture a debug log

Show Debug on the main screen → Copy. Pastes the entire ring buffer (~80 lines) to clipboard. Share that log when reporting issues.

### Inspect templates on disk

```powershell
adb shell run-as com.handyvoice.v2 ls files/templates
adb shell run-as com.handyvoice.v2 cat files/templates/start/take_1.json | head -c 500
```

Each `take_N.json` is `{format, label, mfcc: [[...], ...]}`. If `mfcc` is empty, the capture failed silently (probably a permission revocation between record and save).

### Reset everything

```powershell
adb shell pm clear com.handyvoice.v2
```

Wipes prefs and templates. Next launch is exactly like first install.

### Run unit tests

The MFCC + DTW math has 14 unit tests that run on the JVM (no emulator):

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

If those fail, the engine is broken; do not waste time debugging on-device. If they pass, on-device issues are integration, permissions, or audio routing — never the math.

## When something is truly wrong

Order of escalation:

1. **Capture a debug log.** Show Debug → Copy.
2. **Try `v2-web/` with the same connection key.** Isolates Handy issues from app issues.
3. **Reset templates and retrain.** Roughly 80% of "wrong match" reports go away after a fresh recording session.
4. **Reset everything (`adb shell pm clear`).** Eliminates prefs-corruption hypotheses.
5. **Re-install the debug APK.** Eliminates installation-corruption hypotheses.

If those don't help, file an issue with the debug log, the device firmware version, and the Android version of the phone.
