package com.nous.hermes.phone

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Owns the proot + rootfs + Hermes install lifecycle.
 *
 * Everything mutates the real filesystem under [Constants.BASE_DIR] through
 * [RootShell] (su). The app-private dir is only a staging area for downloaded /
 * imported artifacts.
 *
 * Empirical rules baked in (docs §10):
 *  - Termux proot (linker64 interpreter) + PROOT_LOADER + libandroid-shmem/libtalloc;
 *  - NO --link2symlink (data partitions support real hardlinks; uv breaks otherwise);
 *  - run proot through real `su -c` (proot -0 is a fake root and cannot chown);
 *  - TMPDIR host-side for proot, TMPDIR=/tmp inside the rootfs.
 */
object RootfsManager {
    private const val TAG = "RootfsManager"

    fun stagedArchive(context: Context): File = File(context.filesDir, Constants.ROOTFS_ARCHIVE)

    /** One-shot readiness probe (single su round-trip). */
    fun isReadyQuick(): Boolean {
        val r = RootShell.exec(
            "[ -x ${Constants.PROOT} ] && [ -f ${Constants.ROOTFS_MARKER} ] && " +
                "[ -f ${Constants.HERMES_MARKER} ] && echo READY || echo NOT",
            20_000
        )
        return r.output.contains("READY")
    }

    /**
     * Full pipeline: root check -> proot assets -> rootfs -> Hermes install ->
     * verify. Idempotent: already-completed stages are skipped.
     */
    fun setup(context: Context, rootfsUrl: String) {
        SetupState.phase(SetupState.Phase.CHECKING, "Requesting root…")
        if (!RootShell.isRootAvailable()) {
            throw IllegalStateException("No root access: grant su permission (Magisk/superuser)")
        }

        ensureBaseDirs()
        installProotAssets(context)
        installScripts(context)

        if (!rootfsReady()) {
            SetupState.phase(SetupState.Phase.DOWNLOADING, "Preparing rootfs…")
            val archive = stagedArchive(context).takeIf { it.isFile && it.length() > 0 }
                ?: downloadRootfs(context, rootfsUrl)
            val compression = detectCompression(archive)
            SetupState.phase(SetupState.Phase.EXTRACTING, "Extracting rootfs ($compression)…", -1)
            extractRootfs(archive, compression)
            writeRootfsDefaults()
            archive.delete()
            mark(Constants.ROOTFS_MARKER)
        } else {
            SetupState.appendLog("rootfs already present, skipping extraction")
        }

        if (!hermesReady()) {
            SetupState.phase(SetupState.Phase.INSTALLING, "Installing Hermes (apt + install.sh)…", -1)
            val rc = runScriptStreaming(scriptPath(Constants.SCRIPT_INSTALL))
            if (rc != 0) throw IllegalStateException("Hermes install script exited with code $rc")

            SetupState.phase(SetupState.Phase.VERIFYING, "Verifying Hermes…", -1)
            if (!verifyHermes()) throw IllegalStateException("hermes --version check failed")
            mark(Constants.HERMES_MARKER)
        } else {
            SetupState.appendLog("Hermes already installed, skipping")
        }

        if (!webuiReady()) {
            installWebui(context)
        } else {
            SetupState.appendLog("Hermes WebUI already installed, skipping")
        }
    }

    /**
     * Runs `hermes doctor` (optionally `--fix`) inside proot and streams the
     * report into the setup log so it can be read from the app panel.
     */
    fun runDoctor(context: Context, fix: Boolean) {
        if (!RootShell.isRootAvailable()) {
            throw IllegalStateException("No root access: grant su permission (Magisk/superuser)")
        }
        ensureBaseDirs()
        installScripts(context)

        SetupState.phase(
            SetupState.Phase.VERIFYING,
            if (fix) "Running hermes doctor --fix…" else "Running hermes doctor…",
            -1
        )
        val rc = runScriptStreaming(
            scriptPath(Constants.SCRIPT_DOCTOR),
            if (fix) listOf("--fix") else emptyList()
        )
        SetupState.appendLog("doctor exit code $rc")
        SetupState.phase(
            SetupState.Phase.IDLE,
            if (fix) "Repair complete (doctor exit code $rc)" else "Diagnosis complete (doctor exit code $rc)"
        )
    }

    /** Re-runs the official installer to upgrade the Hermes agent in place. */
    fun updateHermes(context: Context) {
        if (!RootShell.isRootAvailable()) {
            throw IllegalStateException("No root access: grant su permission (Magisk/superuser)")
        }
        ensureBaseDirs()
        installProotAssets(context)
        installScripts(context)

        SetupState.phase(SetupState.Phase.INSTALLING, "Updating Hermes (install.sh)…", -1)
        val rc = runScriptStreaming(scriptPath(Constants.SCRIPT_INSTALL))
        if (rc != 0) throw IllegalStateException("Hermes update script exited with code $rc")

        SetupState.phase(SetupState.Phase.VERIFYING, "Verifying Hermes…", -1)
        if (!verifyHermes()) throw IllegalStateException("hermes --version check failed")
        mark(Constants.HERMES_MARKER)
        SetupState.appendLog("Hermes updated")
    }

    /** Re-downloads the configured WebUI tarball and replaces the installed copy. */
    fun updateWebui(context: Context) {
        if (!RootShell.isRootAvailable()) {
            throw IllegalStateException("No root access: grant su permission (Magisk/superuser)")
        }
        ensureBaseDirs()
        installScripts(context)
        installWebui(context)
    }

    /**
     * Removes the installed runtime. [deleteHermesHome] also deletes sessions,
     * config and API keys under ~/.hermes; otherwise that directory is kept so
     * a reinstall keeps its state.
     */
    fun uninstall(context: Context, deleteHermesHome: Boolean) {
        val targets = mutableListOf(
            Constants.BIN_DIR, Constants.LIB_DIR, Constants.ROOTFS_DIR,
            Constants.TMP_DIR, Constants.SCRIPTS_DIR, Constants.WEBUI_DIR,
            Constants.ROOTFS_MARKER, Constants.HERMES_MARKER
        )
        if (deleteHermesHome) targets += Constants.HERMES_HOME
        val quoted = targets.joinToString(" ") { ShellUtils.shellQuote(it) }

        // toybox has no reliable pkill, so walk /proc and kill only processes
        // whose cmdline points into our base dir. proot survives force-stop and
        // would otherwise keep writing into the directories being deleted.
        val cmd = buildString {
            append("for p in /proc/[0-9]*; do [ -r \"\$p/cmdline\" ] || continue; ")
            append("c=$(tr '\\0' ' ' < \"\$p/cmdline\" 2>/dev/null); ")
            append("case \"\$c\" in ")
            append("*${Constants.BASE_DIR}/bin/proot*|*${Constants.BASE_DIR}/scripts/${Constants.SCRIPT_RUN}*) ")
            append("kill -9 \"\${p#/proc/}\" 2>/dev/null ;; esac; done; ")
            append("sleep 1; rm -rf $quoted; ")
            append("[ -e ${Constants.ROOTFS_DIR} ] && { sleep 1; rm -rf $quoted; }; echo done")
        }
        val r = RootShell.exec(cmd, 180_000)
        if (!r.output.contains("done")) throw IllegalStateException("Uninstall failed: ${r.output.takeLast(300)}")

        val files = context.filesDir
        listOf(
            Constants.ROOTFS_ARCHIVE, "${Constants.ROOTFS_ARCHIVE}.part",
            "webui.tar.gz", "webui.tar.gz.part"
        ).forEach { File(files, it).delete() }
        listOf("proot-stage", "scripts-stage", "skills-stage").forEach {
            File(files, it).deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- proot

    fun ensureBaseDirs() {
        val cmd = listOf(
            Constants.BIN_DIR, Constants.LIB_DIR, Constants.ROOTFS_DIR,
            Constants.HERMES_HOME, Constants.TMP_DIR, Constants.SCRIPTS_DIR,
            Constants.WEBUI_DIR,
            "${Constants.HERMES_HOME}/logs", "${Constants.HERMES_HOME}/shots"
        ).joinToString(" ") { ShellUtils.shellQuote(it) }
        val r = RootShell.exec("mkdir -p $cmd && chmod 755 ${Constants.BASE_DIR}")
        if (!r.ok) throw IllegalStateException("Failed to create ${Constants.BASE_DIR}: ${r.output}")
        // Best effort: match the SELinux label under which the tested proot
        // setup ran (/data/local/tmp is shell_data_file). Ignored if chcon is
        // unavailable or denied.
        RootShell.exec("chcon -R u:object_r:shell_data_file:s0 ${Constants.BASE_DIR} 2>/dev/null; echo done")
    }

    fun installProotAssets(context: Context) {
        val stage = File(context.filesDir, "proot-stage").apply { mkdirs() }
        val lib = File(stage, "lib").apply { mkdirs() }
        copyAsset(context, "proot/proot", File(stage, "proot"))
        copyAsset(context, "proot/loader", File(stage, "loader"))
        copyAsset(context, "proot/lib/libandroid-shmem.so", File(lib, "libandroid-shmem.so"))
        copyAsset(context, "proot/lib/libtalloc.so.2", File(lib, "libtalloc.so.2"))

        val cmd = buildString {
            append("cp -f ${ShellUtils.shellQuote(stage.absolutePath + "/proot")} ${Constants.PROOT}; ")
            append("cp -f ${ShellUtils.shellQuote(stage.absolutePath + "/loader")} ${Constants.LOADER}; ")
            append("cp -f ${ShellUtils.shellQuote(lib.absolutePath + "/libandroid-shmem.so")} ${Constants.LIB_DIR}/; ")
            append("cp -f ${ShellUtils.shellQuote(lib.absolutePath + "/libtalloc.so.2")} ${Constants.LIB_DIR}/; ")
            append("chmod 755 ${Constants.PROOT} ${Constants.LOADER}; ")
            append("chmod 644 ${Constants.LIB_DIR}/*; ")
            append("chown 0:0 ${Constants.PROOT} ${Constants.LOADER} 2>/dev/null; ")
            append("chcon -R u:object_r:shell_data_file:s0 ${Constants.BIN_DIR} ${Constants.LIB_DIR} 2>/dev/null; ")
            append("echo installed")
        }
        val r = RootShell.exec(cmd)
        if (!r.output.contains("installed")) throw IllegalStateException("Failed to deploy proot: ${r.output}")
        stage.deleteRecursively()
    }

    fun installScripts(context: Context) {
        val stage = File(context.filesDir, "scripts-stage").apply { mkdirs() }
        val names = listOf(
            Constants.SCRIPT_RUN, Constants.SCRIPT_INSTALL,
            Constants.SCRIPT_VERIFY, Constants.SCRIPT_WEBUI,
            Constants.SCRIPT_DOCTOR
        )
        for (name in names) {
            val hermesUrl = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString(Constants.PREF_HERMES_URL, Constants.DEFAULT_HERMES_URL)
                ?.ifBlank { Constants.DEFAULT_HERMES_URL } ?: Constants.DEFAULT_HERMES_URL
            val body = context.assets.open("scripts/$name").bufferedReader().use { it.readText() }
                .replace("@BASE@", Constants.BASE_DIR)
                .replace("@PORT@", Constants.WEBUI_PORT.toString())
                .replace("@CONTROL_PORT@", Constants.CONTROL_PORT.toString())
                .replace("@HERMES_INSTALL_URL@", hermesUrl)
            File(stage, name).writeText(body)
        }

        val skillStage = File(context.filesDir, "skills-stage/phone-control").apply { mkdirs() }
        File(skillStage, "SKILL.md").writeText(
            context.assets.open("skills/phone-control/SKILL.md").bufferedReader().use { it.readText() }
        )

        val cmd = buildString {
            append("mkdir -p ${Constants.SCRIPTS_DIR} ${Constants.HERMES_HOME}/skills/phone-control; ")
            for (name in names) {
                append("cp -f ${ShellUtils.shellQuote("${stage.absolutePath}/$name")} ${Constants.SCRIPTS_DIR}/$name; ")
            }
            append("chmod 755 ${Constants.SCRIPTS_DIR}/*; ")
            append("cp -f ${ShellUtils.shellQuote("${skillStage.absolutePath}/SKILL.md")} ${Constants.HERMES_HOME}/skills/phone-control/SKILL.md; ")
            append("echo installed")
        }
        val r = RootShell.exec(cmd)
        if (!r.output.contains("installed")) throw IllegalStateException("Failed to deploy scripts: ${r.output}")
        stage.deleteRecursively()
        File(context.filesDir, "skills-stage").deleteRecursively()
    }

    // --------------------------------------------------------------- rootfs

    fun rootfsReady(): Boolean = rootTest("-f ${Constants.ROOTFS_MARKER}")

    fun hermesReady(): Boolean = rootTest("-f ${Constants.HERMES_MARKER}")

    /** Copy a user-picked rootfs archive into the staging area. */
    fun importRootfsFromUri(context: Context, uri: Uri) {
        val target = stagedArchive(context)
        SetupState.phase(SetupState.Phase.DOWNLOADING, "Importing local rootfs…", 0)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open the selected file")
        if (target.length() == 0L) throw IOException("The selected file is empty")
        SetupState.appendLog("Imported ${target.length() / 1_048_576} MB")
    }

    private fun downloadRootfs(context: Context, url: String): File {
        if (url.isBlank()) throw IllegalStateException("rootfs URL is empty; enter a URL or choose a local package")
        val target = stagedArchive(context)
        val part = File(context.filesDir, "${Constants.ROOTFS_ARCHIVE}.part")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HermesPhone")
        }
        var downloaded = if (part.isFile) part.length() else 0L
        if (downloaded > 0) connection.setRequestProperty("Range", "bytes=$downloaded-")
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) throw IOException("Download failed: HTTP $code")
        val append = code == HttpURLConnection.HTTP_PARTIAL
        if (!append) downloaded = 0L

        val remaining = connection.contentLengthLong
        val total = if (remaining > 0) downloaded + remaining else -1L
        ensureFreeSpace(context.filesDir, if (total > 0) total else 2L * 1024 * 1024 * 1024)

        SetupState.phase(SetupState.Phase.DOWNLOADING, "Downloading rootfs…", if (total > 0) 0 else -1)
        connection.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(1 shl 16)
                var lastReport = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = ((downloaded * 100) / total).toInt()
                        if (pct != lastReport) {
                            SetupState.progress(pct)
                            lastReport = pct
                        }
                    }
                }
            }
        }
        connection.disconnect()

        verifyShaIfConfigured(context, part)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    private fun detectCompression(file: File): String {
        val magic = ByteArray(6)
        val read = file.inputStream().use { it.read(magic) }
        return when {
            read >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> "gzip"
            read >= 6 && magic[0] == 0xfd.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7a.toByte() -> "xz"
            read >= 3 && magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte() -> "bzip2"
            else -> "none"
        }
    }

    private fun extractRootfs(archive: File, compression: String) {
        val flag = when (compression) {
            "gzip" -> "z"
            "xz" -> "J"
            "bzip2" -> "j"
            else -> ""
        }
        val cmd = "rm -rf ${Constants.ROOTFS_DIR} && mkdir -p ${Constants.ROOTFS_DIR} && " +
            "tar x${flag}f ${ShellUtils.shellQuote(archive.absolutePath)} -C ${Constants.ROOTFS_DIR}"
        val r = RootShell.exec(cmd, 30 * 60_000)
        if (!r.ok) throw IllegalStateException("Extraction failed: ${r.output.takeLast(500)}")
    }

    private fun writeRootfsDefaults() {
        // /etc/resolv.conf is absent in bare rootfs tars; without it DNS fails.
        // 10.0.2.3 is QEMU user-net (slirp) DNS — harmless extra entry on a phone.
        val cmd = buildString {
            append("mkdir -p ${Constants.ROOTFS_DIR}/root/.hermes; ")
            append("printf 'nameserver 10.0.2.3\\nnameserver 8.8.8.8\\nnameserver 1.1.1.1\\n' > ${Constants.ROOTFS_DIR}/etc/resolv.conf; ")
            append("echo ok")
        }
        RootShell.exec(cmd, 30_000)
    }

    private fun mark(path: String) {
        RootShell.exec("touch ${ShellUtils.shellQuote(path)} && echo ok")
    }

    // --------------------------------------------------------------- hermes

    private fun scriptPath(name: String) = "${Constants.SCRIPTS_DIR}/$name"

    /** Runs a script with su, streaming output to the log; returns exit code. */
    private fun runScriptStreaming(scriptPath: String, args: List<String> = emptyList()): Int {
        val cmd = buildString {
            append("sh ").append(ShellUtils.shellQuote(scriptPath))
            for (arg in args) append(' ').append(ShellUtils.shellQuote(arg))
        }
        val process = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().forEachLine { line ->
            SetupState.appendLog(line)
            Log.i(TAG, line)
        }
        return process.waitFor()
    }

    // ---------------------------------------------------------------- webui

    fun webuiReady(): Boolean = rootTest("-f ${Constants.WEBUI_DIR}/server.py")

    /**
     * Downloads the pinned hermes-webui tarball and unpacks it inside the rootfs
     * via [Constants.SCRIPT_WEBUI] (which also installs PyYAML/cryptography into
     * the managed Python). Failure is non-fatal: run-hermes.sh falls back to the
     * built-in `hermes dashboard`.
     */
    private fun installWebui(context: Context) {
        try {
            val archive = File(context.filesDir, "webui.tar.gz")
            val url = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString(Constants.PREF_WEBUI_URL, Constants.DEFAULT_WEBUI_URL)
                ?.ifBlank { Constants.DEFAULT_WEBUI_URL } ?: Constants.DEFAULT_WEBUI_URL
            SetupState.phase(SetupState.Phase.DOWNLOADING, "Downloading Hermes WebUI…", 0)
            downloadFile(url, archive, "Hermes WebUI", 300L * 1024 * 1024)

            val staged = "${Constants.TMP_DIR}/webui.tar.gz"
            val cp = RootShell.exec(
                "mkdir -p ${Constants.TMP_DIR} && cp -f ${ShellUtils.shellQuote(archive.absolutePath)} " +
                    "$staged && echo ok",
                120_000
            )
            if (!cp.output.contains("ok")) throw IOException("Failed to copy WebUI package: ${cp.output}")
            archive.delete()

            SetupState.phase(SetupState.Phase.INSTALLING, "Installing Hermes WebUI…", -1)
            val rc = runScriptStreaming(scriptPath(Constants.SCRIPT_WEBUI))
            RootShell.exec("rm -f $staged")
            if (rc != 0 || !webuiReady()) {
                throw IOException("WebUI install failed (exit code $rc). Tap retry.")
            }
            SetupState.appendLog("Hermes WebUI installed")
        } catch (t: Throwable) {
            SetupState.appendLog("WebUI install failed: ${t.message}")
            Log.w(TAG, "webui install failed", t)
            throw if (t is IOException) t else IOException("WebUI install failed: ${t.message}", t)
        }
    }

    private fun downloadFile(url: String, target: File, label: String, minFreeBytes: Long) {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HermesPhone")
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("$label download failed: HTTP $code")
        val total = connection.contentLengthLong
        ensureFreeSpace(target.parentFile, minFreeBytes)
        SetupState.phase(SetupState.Phase.DOWNLOADING, "Downloading $label…", if (total > 0) 0 else -1)
        var downloaded = 0L
        connection.inputStream.use { input ->
            java.io.FileOutputStream(part).use { output ->
                val buffer = ByteArray(1 shl 16)
                var lastReport = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = ((downloaded * 100) / total).toInt()
                        if (pct != lastReport) {
                            SetupState.progress(pct)
                            lastReport = pct
                        }
                    }
                }
            }
        }
        connection.disconnect()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        SetupState.appendLog("$label download complete (${target.length() / 1_048_576} MB)")
    }

    private fun verifyHermes(): Boolean {
        val process = ProcessBuilder(
            "su", "-c", "sh ${ShellUtils.shellQuote(scriptPath(Constants.SCRIPT_VERIFY))}"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val rc = process.waitFor()
        SetupState.appendLog(output.trim())
        return rc == 0 && output.contains("Hermes")
    }

    // ---------------------------------------------------------------- utils

    private fun rootTest(expr: String): Boolean =
        RootShell.exec("[ $expr ] && echo yes || echo no", 20_000).output.contains("yes")

    private fun ensureFreeSpace(dir: File, requiredBytes: Long) {
        val stat = StatFs(dir.absolutePath)
        val available = stat.availableBytes
        if (available < requiredBytes) {
            throw IOException("Not enough space: need about ${requiredBytes / 1_048_576} MB, available ${available / 1_048_576} MB")
        }
    }

    private fun verifyShaIfConfigured(context: Context, file: File) {
        val expected = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString("rootfs_sha256", null)?.trim().orEmpty()
        if (expected.isEmpty()) {
            SetupState.appendLog("No sha256 configured, skipping check")
            return
        }
        SetupState.phase(SetupState.Phase.DOWNLOADING, "Verifying sha256…", -1)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IOException("sha256 mismatch: $actual != $expected")
        }
        SetupState.appendLog("sha256 check passed")
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.setExecutable(true)
    }
}
