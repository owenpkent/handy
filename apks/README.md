# APKs

Built installers land here, version-named, so there's a stable place to grab
one for sideloading (USB, Google Drive, a local download link, etc).

## How they get here

`android-v2`'s `copyDebugApkToRoot` Gradle task runs automatically after
`assembleDebug` and copies the debug APK in as:

```
SmartDongV2-v<versionName>-debug.apk
```

So a normal build refreshes it:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; cd C:\Users\Owen\dev\handy-v1\android-v2; .\gradlew.bat :app:assembleDebug
```

## Versioning

The filename tracks `versionName` from `android-v2/app/build.gradle.kts`
(`defaultConfig`). To cut a new versioned APK, bump `versionName` (and
`versionCode`) there, then rebuild. Same version rebuilt overwrites its file.

## Git

The `.apk` files themselves are gitignored (build artifacts, ~8 MB each). Only
this README is tracked, so the folder exists in a fresh clone.
