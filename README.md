# Capsule

Capsule is a free, open-source Android app that uses the **Work Profile**
feature to run apps in an isolated space, separate from your main profile:
install or clone apps into it, freeze them when idle, and move files across
the boundary.

Capsule is a Kotlin / Jetpack Compose / Material 3 rewrite of Haven, a
modernized fork of Shelter.

## What's new over Shelter / Haven

- **Survives a Capsule reinstall.** Reinstalling Capsule in the main
  profile re-attaches it to the existing work profile: cloning, freezing
  and settings keep working, with no re-provisioning.
- **Work-profile certificates.** Install and remove certificates in the
  work profile's trust store.
- **Main → work file picking.** Opt-in: apps in the main profile pick
  files from the work profile through Android's system pickers — no
  storage permissions needed.
- **Paused-profile handling.** When Work Mode is off, the app no longer
  kicks you out — it opens with the profile state and offers to resume the
  profile right there.

## Also included

- Install, clone and uninstall apps inside the profile
- Freeze apps to stop them from running; auto-freeze on screen lock
- "Unfreeze & Launch" and "Freeze all" home-screen shortcuts
- File Shuttle: give your apps access to the other profile's files through
  the system Files app and document pickers
- Managed app restrictions editor; cross-profile widget and connected-app
  allowlists

## Requirements

- Android 8.0 (API 26) or later
- A device with intact Work Profile support (AOSP-like systems work best;
  heavily customized vendor ROMs may break the underlying platform feature)

## Building

Debug builds use the public keystore committed in `app/debug.keystore`:

```
./gradlew assembleDebug
```

Release builds require signing properties pointing at a keystore outside
the project tree (see `docs/RELEASING.md`).

## License

GPLv3. See [LICENSE](LICENSE).

## Credits

- [Shelter](https://github.com/PeterCxy/Shelter) — the original work
  profile manager this project is built on
- [Haven](https://github.com/Kenneth-Cho-InfoSec/Haven) — the direct
  upstream: the app restrictions editor, the cross-profile file picker
  settings and the setup wizard come from it
- [TestDPC](https://github.com/googlesamples/android-testdpc) — Google's
  sample device policy controller, the reference for the DPC code
