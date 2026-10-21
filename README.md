# Hermes Phone

Run [Hermes Agent](https://github.com/NousResearch/hermes-agent) entirely inside an Android app — no Termux, no real chroot, no ADB.

The app downloads an Ubuntu arm64 rootfs, boots Hermes inside [proot](https://proot-me.github.io/), and shows the native [hermes-webui](https://github.com/nesquena/hermes-webui) chat interface in a WebView. Everything lives under `/data/local/hermes` and runs in the foreground so Android does not kill it.

## Features

- One-tap first-run setup: rootfs → Hermes installer → WebUI
- All three install sources are configurable (rootfs tarball, Hermes `install.sh` URL, WebUI tarball)
- In-app chat via hermes-webui (`127.0.0.1:8787`), with a local fallback dashboard
- Update Hermes or the WebUI in place from the setup panel
- Uninstall / reset, optionally keeping sessions, config and API keys
- Boot autostart and a local control API for the bundled `phone-control` skill (tap/swipe/screenshot/launch apps via `su`)
- WebView never overlaps the system bars or IME

## Requirements

- Android 8.0+ (API 26), **arm64**
- Root via Magisk or similar (used to write `/data/local/hermes` and for the control layer)
- ~4 GB free storage and a network connection for the first install

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64   # JDK 17+
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is about 4 MB; nothing is bundled besides proot and the scripts.

## Usage

1. Launch **Hermes Phone** and grant root when asked.
2. Optionally edit the three URLs (defaults below), then tap **开始安装 / Install**.
3. Wait for the download, extraction and install steps; the app opens the chat UI when `/health` responds.
4. Set your model/API key inside Hermes as usual (e.g. in `~/.hermes/config.yaml` or via the onboarding UI).

Default sources:

| Step | Default |
| --- | --- |
| rootfs | `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz` |
| Hermes installer | `https://hermes-agent.nousresearch.com/install.sh` |
| WebUI | `https://codeload.github.com/nesquena/hermes-webui/tar.gz/c67fd2dd` |

Use the `⋮` button on the middle-right edge (or the system Back gesture) to return to the setup panel, where you can reopen the WebUI, update components, or uninstall.

## How it works

```
MainActivity (WebView) ──▶ 127.0.0.1:8787  hermes-webui
        │
ProotService (foreground) ──▶ proot -0 -r /data/local/hermes/rootfs
        │                       └─ Ubuntu 24.04 + Python + Hermes Agent
RootfsManager ──▶ downloads rootfs / scripts / WebUI tarball
```

proot runs in userspace, so SELinux stays enforcing and no system partitions are mounted. Android-specific workarounds (policy routing, `PROOT_TMP_DIR`, no `--link2symlink`, DNS, IME insets) are documented in [`docs/hermes-android-client.md`](docs/hermes-android-client.md).

## Repository layout

- `app/` — Kotlin app (UI, service, install manager, control API)
- `app/src/main/assets/scripts/` — install / run / verify scripts executed inside proot
- `app/src/main/assets/skills/phone-control/` — Hermes skill for controlling the phone
- `docs/hermes-android-client.md` — design notes, verification log and pitfalls
- `test/` — QEMU/LineageOS test harness and screenshots

## Status

Verified end-to-end on LineageOS 22 (Android 16, arm64) under QEMU: rootfs install, Hermes v0.22.2, CLI, native WebUI chat, control API, boot autostart, uninstall/reset.

## License

Apache-2.0. Third-party components and their licenses are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
