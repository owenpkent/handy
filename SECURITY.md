# Security

## Reporting a problem

Please report security problems privately, not in a public issue.

Use GitHub's private form: **[Report a vulnerability](https://github.com/owenpkent/handy/security/advisories/new)** (also under the repository's Security tab). It reaches only the maintainer.

Include what you found, how to reproduce it, and the app version (Settings, About). This is a one-person project run in spare time, so expect a first reply within about a week. You will be credited in the fix unless you ask not to be.

## What counts

The app controls a motor against a person's body, so safety problems are security problems here. Reports are especially welcome for anything that lets:

- someone other than the user start, change or keep motion running, or stop the user from stopping it;
- motion exceed the speed or stroke limits set in Settings;
- another app or a nearby Bluetooth device take control, or be paired in place of the user's own toy;
- the in-app updater install something that is not a genuine release;
- voice recordings, the Handy connection key or other private data leave the phone.

Only the latest release is supported. The apps under `legacy/` are frozen and receive no fixes.

## Things that are known and intended

- **The signing keystore is committed** (`android-v2/app/signing/handy.keystore`, with the standard Android debug passwords). It lets any machine build an update that installs over an existing copy. It identifies builds; it does not protect them. Anyone can sign an APK with it, so the release repository, not the signature, is what tells you a build is genuine. Only install builds from [the releases page](https://github.com/owenpkent/handy-releases/releases) or ones you built yourself.
- **Builds are debug builds**, installed by sideloading. The app is not distributed through an app store.
- **Voice matching is not access control.** The app matches against the user's own recordings, so other voices usually fail to trigger commands, but this is a convenience, not a lock.
- **The Handy connection key is stored on the phone** in the app's private storage, unencrypted, and sent only to the Handy API.
- **Android backup is on** (`allowBackup="true"`). The app itself sends data only to the Handy API and to GitHub for the update check, but if the phone's own backup is enabled, Android may copy the app's data (settings, including the connection key, and the voice templates) to the user's Google account. This keeps trained voice samples across a phone change. Turn off backup for the app in Android settings if you do not want that.
