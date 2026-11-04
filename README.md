# VaultSync

English | [简体中文](README.zh-CN.md)

VaultSync keeps folders on an Android phone in sync with Git repositories, over HTTPS or SSH.
Tap **Sync** and it commits your changes, merges what changed on the remote, and pushes.
A simple conflict resolver is built in.

It started as a way to sync [Obsidian](https://obsidian.md) notes, and it works well for that, but nothing in it is tied to Obsidian:
any folder of files with a Git remote will do. In the app, each synced folder is called a *vault*.

## Features

**Adding a repository**
- Clone a remote repository into a folder on the phone.
- Turn an existing folder into a new repository and push it to an empty remote.
- Take over a folder that already has a `.git` directory.
- Test the connection before you start.

**Syncing**
- One tap: commit all changes → fetch → merge → push. If the push is rejected because the remote moved on, it fetches and merges again.
- It never rebases or force-pushes; history on the remote is only ever added to.
- Several repositories, each with its own status. Syncs are queued and run one at a time, in a foreground service with a progress notification, and can be cancelled.
- Clear errors: which step failed, the likely cause and what to do, with the raw message and a log one tap away.

**Conflicts**
- The conflicting file is shown as one continuous document, with each conflict inline: keep local, keep remote, keep both (in either order), or edit by hand.
- Line numbers from both versions, highlighting of the lines that differ, and a full-file editor with search that warns about leftover conflict markers.
- Binary files and files deleted on one side get whole-file choices.
- Resolve everything as local or remote at once, or abort the merge.

**History**
- Commit history of the current branch, marking commits that are not pushed yet.
- Each commit's changed files with a diff: line numbers, word-level highlighting, collapsed unchanged lines, wrap on or off.

**Authentication and network**
- HTTPS with a username and token.
- SSH with keys generated (Ed25519) or imported in the app (OpenSSH and PEM formats, passphrases supported); pick a key per repository.
- Known hosts are recorded on first connection; a changed host key is refused. GitHub's keys are built in, and GitHub SSH switches between ports 22 and 443 with one tap.
- Tokens and private keys are encrypted with a key held in the Android Keystore.
- Optional HTTP proxy; when set, both HTTPS and SSH connections go through it.

**Housekeeping**
- Detects what an interrupted sync can leave behind (lock files, an unfinished merge) and offers to fix it.
- Shows how big the repository has grown and suggests when to tidy it up (Git garbage collection).
- Re-clone a repository into its folder if the folder is missing, for example on a new phone.
- Export the app's setup to a file (tokens and keys optional, encrypted with a passphrase) and restore it later, choosing what to restore.

**Also**
- Per-repository author name and email, and a commit message template with a date (default `vault backup: {{date}}`).
- A log of each sync, clone and maintenance run, with copy and share; a diagnostics zip for bug reports.
- 8 languages: English, 简体中文, 繁體中文, 日本語, Deutsch, Español, Français, Русский, with more possible later.
  Apart from English and Simplified Chinese, the translations are machine-made and have not been reviewed by a person;
  if you know one of these languages, corrections through an issue or pull request are very welcome.
- Light and dark theme, dynamic color on Android 12 and later.

## Limitations

- No automatic sync. Nothing runs on a schedule, and it does not watch other apps in the background; it syncs only when you tap.
- It works on the branch that is checked out. Branch switching, rebase, cherry-pick, submodules and Git LFS are not supported yet; they may be considered later.
- The folders must be ordinary folders in shared storage (for example `Documents/`). Folders from other apps' storage providers are not supported.
- It has only been tested on Android 10 and later; older versions are not planned.

## Getting started

1. Download the APK from [Releases](https://github.com/happyEddiehorse/VaultSync/releases) and install it. Android 10 or later.
2. Open VaultSync and grant file access when asked (on Android 11 and later this is "All files access").
3. Tap **+** at the bottom right, choose how to start (clone, new repository, or existing repository) and pick the folder.
4. Enter the remote URL:
   - HTTPS, e.g. `https://github.com/user/repo.git`, with your username and a personal access token.
     On GitHub, a fine-grained token with *Contents: read and write* on the repository is enough.
   - SSH, e.g. `git@github.com:user/repo.git`: generate a key in the app, copy the public key and add it to your Git host
     (on GitHub, as a deploy key with write access or in your account's SSH keys).
5. Check the author name and email, confirm, and wait for the first clone or push.
6. From then on, tap **Sync** whenever you want the phone and the remote to match.

## Using it with Obsidian

- Keep the vault in shared storage, such as `Documents/<vault name>`; Obsidian shows the exact location under *Manage vaults*.
- Use it together with the desktop [obsidian-git](https://github.com/Vinzent03/obsidian-git) plugin on the same repository. The default commit message matches the plugin's.
- The app can add recommended ignore rules for files that differ on every device, such as `.obsidian/workspace.json`, so they do not cause conflicts.
- Sync on the phone before you start editing and after you finish, and do the same on the desktop, to keep conflicts rare.

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
