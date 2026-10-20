# Third-party notices

Original code in this repository is licensed under the Apache License 2.0
(see `LICENSE`). It bundles or installs the following third-party components:

| Component | License | Source |
|---|---|---|
| proot (Termux build, bundled under `app/src/main/assets/proot`) | GPL-2.0 | https://github.com/termux/proot |
| libandroid-shmem (bundled) | BSD-2-Clause | https://github.com/termux/libandroid-shmem |
| talloc (bundled) | LGPL-3.0-or-later | https://talloc.samba.org/ |
| Hermes Agent (installed at runtime) | MIT, Copyright (c) 2025 Nous Research | https://github.com/NousResearch/hermes-agent |
| hermes-webui (downloaded at runtime) | MIT, Copyright (c) 2025 Hermes Web UI Contributors | https://github.com/nesquena/hermes-webui |
| AndroidX / Kotlin / coroutines (Maven dependencies) | Apache-2.0 | https://developer.android.com/jetpack |

## Notes

- `proot` is a standalone executable invoked via `exec`, not linked into the
  app. Its complete corresponding source is available at the link above.
  Redistributors of an APK containing the bundled binaries must comply with
  the GPL-2.0 terms for `proot` (and LGPL-3.0 for `talloc`).
- Hermes Agent and hermes-webui are not embedded in the APK. The app downloads
  and installs them at runtime, retaining their upstream MIT license notices.
- Ubuntu Base rootfs is downloaded at runtime and is subject to its own
  licensing (Ubuntu Base image, Canonical).
