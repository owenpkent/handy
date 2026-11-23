# Contributing

Thank you for looking. This project exists so that people who cannot easily use their hands, or whose speech ordinary voice recognition does not understand, can control an intimate device on their own. It started as one person's tool and is open so that others can use it, adapt it, and help it support more people and more devices.

You do not need to write code to help. Some of the most useful contributions are listed first.

## Ways to help

- **Tell us how a device behaves.** Lovense support was written from public protocol notes and has barely been tested on hardware. If you own a toy, open a [device report](https://github.com/owenpkent/handy/issues/new?template=device_report.yml), whether it works or not. The app's Toy test panel has a Copy report button that gathers what we need.
- **Report a bug.** Use the [bug report](https://github.com/owenpkent/handy/issues/new?template=bug_report.yml) form. Short is fine.
- **Tell us what gets in your way.** If the app is hard to use with your body, your voice or your assistive technology, that is a bug here, not a nice-to-have. Open an [idea or request](https://github.com/owenpkent/handy/issues/new?template=feature_request.yml).
- **Improve the docs**, especially where a step assumed something you did not know.
- **Write code.** See below.

This is a project about sex and disability. Be matter-of-fact about both, in issues and in code review. Nobody has to explain their body or their reasons to get help here. The [Code of Conduct](CODE_OF_CONDUCT.md) applies everywhere in the project.

## What is in the repository

| Folder | What it is | Open to changes? |
|---|---|---|
| [`android-v2/`](android-v2/) | The app. Kotlin, Android. | Yes, this is where the work is. |
| [`docs/`](docs/), [`android-v2/docs/`](android-v2/docs/) | Reference and design notes. | Yes. |
| [`hardware/`](hardware/) | Early CAD for a DIY wearable. | Ideas and review welcome; open an issue first. |
| [`legacy/`](legacy/) | Older apps, kept for reference. | No. Frozen, fixes are not accepted. |

Start with [`android-v2/README.md`](android-v2/README.md) and [`android-v2/docs/ARCHITECTURE.md`](android-v2/docs/ARCHITECTURE.md). [`CLAUDE.md`](CLAUDE.md) is a long, file-by-file reference written for AI coding assistants; it is also the most complete map of the code for people.

## Building and testing

You need JDK 17 or newer and the Android SDK. Android Studio provides both: open the `android-v2/` folder and let it sync.

From a terminal, in `android-v2/`:

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests, a few seconds, no device needed
./gradlew :app:assembleDebug       # build the debug APK
./gradlew :app:installDebug        # install on an attached phone or emulator
```

On Windows use `.\gradlew.bat` in place of `./gradlew`. If Gradle cannot find Java, point `JAVA_HOME` at a JDK (Android Studio ships one in its `jbr` folder). More build problems are covered in [TROUBLESHOOTING](android-v2/docs/TROUBLESHOOTING.md).

No device is needed for most work. The voice matcher, the command dispatch, the motion engines and the Lovense transport are all written to run in plain JVM tests against fakes, and the app runs on the Android emulator.

The same tests and a debug build run automatically on every pull request.

### Checks

These run by themselves; you only need to act when one goes red.

- **Tests and build** (`android-tests.yml`): the unit tests and a debug build, on every pull request that touches `android-v2/`.
- **Dependency lockfile**: `android-v2/app/gradle.lockfile` lists every library that ships in the app. If you add or bump a dependency in `app/build.gradle.kts`, run `./gradlew :app:dependencies --write-locks` from `android-v2/` and commit the changed lockfile, or the check fails. Dependabot's pull requests need the same step.
- **Known vulnerabilities** (`osv-diff.yml`, `osv.yml`): a pull request fails if it introduces a library with a published advisory. The whole tree is rescanned every week.
- **CodeQL** (`codeql.yml`): static analysis of the Kotlin code. Findings appear on the pull request.
- **Fuzzing** (`nightly-fuzz.yml`): the tests in `app/src/test/.../fuzz/` are [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz targets for code that reads input the app does not control (replies from a toy, release tags, stored history, microphone audio). In a normal test run they only replay their saved inputs, so they are fast. Every night each one fuzzes for ten minutes, and a failure opens a "Fuzz crash" issue with the input attached. To fix one, put the input file where the issue says, run the tests to reproduce it, fix the bug, and commit the input next to the fix so it stays as a regression test. Do not loosen the checks in the target to make it pass. If you add a fuzz target, add its class name to the list in `nightly-fuzz.yml`; a check fails if the two differ. To fuzz locally, set `JAZZER_FUZZ=1` and run one target: `./gradlew :app:testDebugUnitTest --tests '*LovenseReplyFuzzTest'`.

Code that parses input from outside the app (a device, the network, a file) should come with a fuzz target.

## Making a change

1. For anything bigger than a small fix, open an issue first so we can agree on the approach before you spend time on it.
2. Fork, and branch from `main`.
3. Keep the pull request to one topic. Small pull requests get reviewed much sooner.
4. Use [conventional commit](https://www.conventionalcommits.org/) subjects: `feat:`, `fix:`, `docs:`, `refactor:`, `chore:`.
5. Fill in the pull request template. **Say plainly what you did not test.** "Not tried on a real toy" is a normal, acceptable thing to write, and far better than leaving it out.

### What reviewers look for

**Stopping always works.** This app drives a motor against someone's body, often for a person who cannot quickly reach the device or the phone. Every stop path (voice, tap, Picture-in-Picture, idle auto-stop, the notification, a lost connection) must keep working, and a change that touches motion should come with a test showing that it does. A new way to start motion needs a matching way to stop it. The speed and stroke limits in Settings are caps that nothing may exceed.

**Usable without typing, gripping or precision.** New screens and controls should work by tap alone, with large targets (48 dp minimum, 56 dp for anything primary), no long-press, no drag-only gestures, no timed interactions, and text that still fits at 200% font size and in split-screen. Settings apply as they change; there are no Save buttons.

**Logic lives where it can be tested.** Keep decisions out of Activities and Android classes and in plain Kotlin that a JVM test can drive. The existing code uses small seams for this (`DeviceTransport`, `LovenseGattPort`, `ListeningSession`). New logic should come with a unit test.

**No new voice commands without a strong reason.** The spoken vocabulary is four words on purpose. Each added word narrows the acoustic gap between the others and makes recognition worse for everyone. New actions usually belong on a button.

**The app does not phone home.** Voice samples are recorded and matched on the device, and there are no analytics and no accounts. The only network calls are the device's own API and the update check. Please do not add others.

**Docs move with the code.** If behaviour changes, update the matching file in [`android-v2/docs/`](android-v2/docs/) and the README. If you add, remove or repurpose a source file, update its row in [`CLAUDE.md`](CLAUDE.md).

### Adding a device

Devices sit behind the `DeviceTransport` interface in `android-v2/app/src/main/java/com/handyvoice/v2/api/`. A transport only moves bytes: connect, start, stop, set velocity, set stroke range. Ramps, limits, patterns and state stay in the shared client above it, so a new device gets voice, patterns and every stop path without extra work. [`android-v2/docs/LOVENSE.md`](android-v2/docs/LOVENSE.md) shows how the Bluetooth transport was researched and built, including the mistakes.

### Using AI tools

AI-assisted contributions are welcome, and much of this codebase was written that way. You are responsible for what you submit: read it, run the tests, and do not describe something as tested when only a model said it would work.

## Security

Please report security problems privately. See [SECURITY.md](SECURITY.md).

## Licence

The project is under the [MIT License](LICENSE). By contributing, you agree that your contribution is licensed the same way.
