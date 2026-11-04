# VaultSync

English | [简体中文](README.zh-CN.md)

VaultSync is an Android app that syncs one or more [Obsidian](https://obsidian.md) vaults with a Git remote (GitHub or any Git host), by hand.
It is meant to work side by side with the desktop [obsidian-git](https://github.com/Vinzent03/obsidian-git) plugin: same repository, same ignore rules, no surprises on either side.
It is a Git sync tool for Obsidian, not a general Git client.

## Features

- One tap sync: commit, pull and merge, push.
- Clone an existing repository, start a new one from an existing vault, or take over a vault that already has a `.git` folder.
- Resolve merge conflicts inside the app, hunk by hunk, with manual editing; nothing is overwritten silently.
- HTTPS (token) and SSH (key generated or imported in the app), optional proxy.
- Several vaults, each synced on its own.
- Repairs leftovers after an interrupted sync (lock files, unfinished merges).
- Encrypted backup and restore of the app settings, tokens and keys.
- 8 UI languages.

Sync only runs when you ask for it. There is no scheduled or background sync.

## Requirements

- Android 10 (API 29) or later.
- Vaults stored in shared storage (for example `Documents/`); the app asks for the "All files access" permission on Android 11 and later.

## Building

JDK 21 and the Android SDK are needed.

```bash
./gradlew :gitcore:test        # unit tests of the sync engine (plain JVM)
./gradlew :app:assembleDebug   # debug APK in app/build/outputs/apk/debug/
```

The `gitcore` module holds the Git and sync logic and has no Android dependency; `app` holds the Compose UI and the platform code.
JGit is pinned to 5.13.x: newer versions call JDK APIs that Android 10 does not have.

## License

VaultSync is free software under the [GNU General Public License v3.0](LICENSE).

The name "VaultSync" and the app icon are not licensed under the GPL (GPL-3.0 section 7(e)).
If you distribute a modified version, please give it a different name, icon and application ID, so that it is not mistaken for this app (section 7(c)).

VaultSync is not affiliated with Obsidian.
