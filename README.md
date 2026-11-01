# Capsule

Capsule is a Free and Open-Source (FOSS) app that leverages Android's
**Work Profile** feature to provide an isolated space that you can install or
clone apps into.

Capsule is a Jetpack Compose / Material 3 continuation of the
[Shelter](https://github.com/PeterCxy/Shelter) lineage of work profile
managers, rebuilt on a modern UI and build stack.

## Features

- Install, clone and uninstall apps inside a managed Work Profile
- "Freeze" (hide) apps inside the profile to stop them from running
- Auto-freeze apps on screen lock, with a configurable delay and
  foreground-app skipping
- Unfreeze & Launch shortcuts, including batch shortcuts for groups of apps
- File Shuttle: browse and copy files across the profile boundary through the
  system Documents UI
- Cross-profile system file pickers (opt-in)
- Managed app restrictions (DPC application restrictions) editor
- Cross-profile widget providers and connected-app (cross-profile packages)
  allowlists
- Hardened cross-profile IPC: HMAC-signed intents with nonce replay
  protection, plus a signature-level permission on the exported bridge
  activity
- Diagnostic report for provisioning failures on customized ROMs

## Requirements

- Android 7.0 (API 24) or later
- A device with intact Work Profile support (AOSP-like systems work best;
  heavily customized vendor ROMs may break the underlying platform feature)

## Building

Debug builds use the public keystore committed in `app/debug.keystore`:

```
./gradlew assembleDebug
```

Release builds require signing properties pointing at a keystore outside the
project tree (see `docs/RELEASING.md`).

## License

GPLv3. See [LICENSE](LICENSE).
