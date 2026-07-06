# App signing & the "package conflicts" update error

If an update fails with:

> **App not installed as package conflicts with an existing package.**

it is **not** a bug in the app. Android is refusing the install because the new
APK is signed with a **different key** than the copy already on the phone. Two
APKs with the same package name (`com.handyvoice.v2`) but different signatures
are treated as a conflict, and Android will not install one over the other.

## Why it happens

Debug builds are signed with `~/.android/debug.keystore`, which is generated
**per machine**. The passwords and alias are identical everywhere
(`android` / `androiddebugkey` / `android`) but the actual certificate is random
per machine. So an APK built on machine B has a different signature than the
build installed from machine A, and the update is rejected.

## The permanent fix (already in the build)

`app/build.gradle.kts` signs every build with a committed keystore at
`app/signing/handy.keystore` **when that file exists** — applied to both the
`debug` and `release` build types. Once every build shares one certificate,
updates always install over the top, from any machine or a cloud build. If the
file is absent the build falls back to the per-machine debug key (old
behaviour), so nothing breaks before it's seeded.

That keystore must be seeded **with the key that signed the app currently on the
phone**, or the *first* update after this change still mismatches and needs a
one-time uninstall.

**Status: done.** The keystore was seeded from Owen's main PC's debug key and
committed in **v0.17** — the first build signed with it — so updates now install
over the top from any machine with no uninstall. The runbook below records how it
was done and is the reference if the key ever needs re-seeding.

---

## Runbook — how it was seeded (ORIGINAL build machine)

"Original machine" = the PC whose `~/.android/debug.keystore` signed the build
that's installed on the phone right now. For this project that's Owen's main
Windows PC (`C:\Users\Owen`). Its debug keystore lives at
`C:\Users\Owen\.android\debug.keystore`.

Run everything below on **that** machine, in PowerShell.

### 1. Seed the shared keystore from the machine's debug key

```powershell
# Copy the debug key that signed the installed app into the committed slot.
Copy-Item "$env:USERPROFILE\.android\debug.keystore" `
          "C:\Users\Owen\dev\handy\android-v2\app\signing\handy.keystore" -Force
```

Confirm it landed:

```powershell
Test-Path "C:\Users\Owen\dev\handy\android-v2\app\signing\handy.keystore"   # -> True
```

(No password entry needed — the standard debug credentials
`android` / `androiddebugkey` / `android` are already wired into
`build.gradle.kts`.)

### 2. Bump the version

Edit `android-v2/app/build.gradle.kts`, `defaultConfig`:

```kotlin
versionCode = 17          // was 16 — must increase
versionName = "0.17"      // was "0.16" — the updater compares this to the release tag
```

### 3. Build & publish

```powershell
cd C:\Users\Owen\dev\handy\android-v2
.\publish-release.ps1 -Notes "Fix update signing (shared keystore)"
```

This builds the debug APK (now signed with the committed key), drops
`apks\SmartDongV2-v0.17-debug.apk`, and cuts the `v0.17` GitHub Release on
`owenpkent/handy-releases` where the in-app updater looks.

### 4. Update on the phone

Open the app → **Settings → About → Check for updates** → install. Because the
new APK carries the **same signature** as the installed app, it installs **over
the top** — no uninstall, all voice recordings preserved.

### 5. Commit the seeded keystore

So every future build (any machine, cloud sessions included) shares the key:

```powershell
cd C:\Users\Owen\dev\handy
git add android-v2/app/signing/handy.keystore
git commit -m "chore: seed shared signing keystore"
git push
```

It's a **debug** keystore — an app identity, not a secret — so committing it is
intentional and safe. Keep it in the repo permanently.

---

## If step 4 STILL says "conflicts"

Then the app on the phone was **not** signed by this machine's debug key — it
came from some other machine. There's no signature-preserving path in that case;
you must uninstall once:

```powershell
# Optional: back up trained voice samples first (needs USB + adb).
adb shell run-as com.handyvoice.v2 tar -c -C files templates > templates-backup.tar
adb uninstall com.handyvoice.v2
adb install "C:\Users\Owen\dev\handy\apks\SmartDongV2-v0.17-debug.apk"
adb shell run-as com.handyvoice.v2 tar -x -C files < templates-backup.tar   # restore samples
```

After this one uninstall onto the committed key, **every future update installs
cleanly** — this is the last time it happens.

## Rules to keep updates working

- **Never delete or replace `app/signing/handy.keystore`.** A new key = another
  one-time uninstall for everyone.
- Moving to a real release keystore later is also a one-time uninstall (different
  signature) — do it deliberately, not by accident.
