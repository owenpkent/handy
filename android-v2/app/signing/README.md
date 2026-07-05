# Signing key

The build signs with the keystore at `handy.keystore` **in this folder** if it
exists (see `signingConfigs` in `../build.gradle.kts`). Committing one fixed key
here means every machine — your PC, a laptop, a cloud build — produces APKs with
the **same signature**, so the in-app updater can install a new build straight
over the top of the old one.

Without this, each machine signs with its own `~/.android/debug.keystore`, the
signatures differ, and Android refuses the update with:

> App not installed as package conflicts with an existing package.

## Install-over-the-top WITHOUT uninstalling (keep your recordings)

Android only accepts an update whose signature matches the **currently installed**
app. So the key committed here must be the *same key that signed the build on your
phone right now* — which is your main PC's debug keystore.

On the machine you normally build from, copy your existing debug keystore into
this folder as `handy.keystore`:

```powershell
Copy-Item "$env:USERPROFILE\.android\debug.keystore" "app\signing\handy.keystore"
```

(Standard debug keystore credentials — already set in `build.gradle.kts`:
store password `android`, key alias `androiddebugkey`, key password `android`.)

Then bump `versionName` / `versionCode`, rebuild, and publish. Because the APK
now carries the same signature as the installed app, it installs over the top —
**no uninstall, voice recordings preserved.**

If the install *still* reports a conflict after this, the copy on your phone was
signed by a different machine's key. In that case a one-time uninstall/reinstall
is unavoidable — but from then on every future update installs cleanly, because
all builds share this committed key.

## Notes

- This is a **debug** keystore. It is fine to commit: it grants no privileged
  access, it only fixes the app's signing identity so updates apply.
- Keep `handy.keystore` here forever. Deleting or replacing it forces another
  one-time uninstall/reinstall for everyone on the old key.
- If you ever move to a proper release keystore, that's also a one-time
  uninstall/reinstall (different signature), so do it deliberately.
