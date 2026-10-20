package com.nous.hermes.phone

/**
 * Central path/port constants. Everything lives under [BASE_DIR] on the real
 * filesystem (ext4/f2fs), never under /storage/emulated/0 (scoped storage).
 */
object Constants {
    const val BASE_DIR = "/data/local/hermes"
    const val BIN_DIR = "$BASE_DIR/bin"
    const val LIB_DIR = "$BASE_DIR/lib"
    const val ROOTFS_DIR = "$BASE_DIR/rootfs"
    const val HERMES_HOME = "$BASE_DIR/hermes-home"
    const val TMP_DIR = "$BASE_DIR/tmp"
    const val SCRIPTS_DIR = "$BASE_DIR/scripts"
    const val WEBUI_DIR = "$BASE_DIR/webui"

    const val PROOT = "$BIN_DIR/proot"
    const val LOADER = "$BIN_DIR/loader"

    const val ROOTFS_MARKER = "$BASE_DIR/.rootfs-ok"
    const val HERMES_MARKER = "$BASE_DIR/.hermes-ok"

    const val WEBUI_PORT = 8787
    const val CONTROL_PORT = 8788

    const val DEFAULT_WEBUI_URL =
        "https://codeload.github.com/nesquena/hermes-webui/tar.gz/c67fd2dd"

    const val DEFAULT_ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

    const val ROOTFS_ARCHIVE = "rootfs.tar.gz"

    const val SCRIPT_RUN = "run-hermes.sh"
    const val SCRIPT_INSTALL = "install-hermes.sh"
    const val SCRIPT_VERIFY = "verify-hermes.sh"
    const val SCRIPT_WEBUI = "install-webui.sh"

    const val PREFS = "hermes_phone"
    const val PREF_ROOTFS_URL = "rootfs_url"
    const val PREF_AUTO_START = "auto_start"

    const val NOTIF_CHANNEL = "hermes_runtime"
    const val NOTIF_ID = 1001
}
