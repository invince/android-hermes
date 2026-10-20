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
        SetupState.phase(SetupState.Phase.CHECKING, "申请 root 权限…")
        if (!RootShell.isRootAvailable()) {
            throw IllegalStateException("未获得 root：请授予 su 权限（Magisk/超级用户）")
        }

        ensureBaseDirs()
        installProotAssets(context)
        installScripts(context)

        if (!rootfsReady()) {
            SetupState.phase(SetupState.Phase.DOWNLOADING, "准备 rootfs…")
            val archive = stagedArchive(context).takeIf { it.isFile && it.length() > 0 }
                ?: downloadRootfs(context, rootfsUrl)
            val compression = detectCompression(archive)
            SetupState.phase(SetupState.Phase.EXTRACTING, "解压 rootfs（$compression）…", -1)
            extractRootfs(archive, compression)
            writeRootfsDefaults()
            archive.delete()
            mark(Constants.ROOTFS_MARKER)
        } else {
            SetupState.appendLog("rootfs 已存在，跳过解压")
        }

        if (!hermesReady()) {
            SetupState.phase(SetupState.Phase.INSTALLING, "安装 Hermes（apt + install.sh）…", -1)
            val rc = runScriptStreaming(scriptPath(Constants.SCRIPT_INSTALL))
            if (rc != 0) throw IllegalStateException("Hermes 安装脚本退出码 $rc")

            SetupState.phase(SetupState.Phase.VERIFYING, "校验 Hermes…", -1)
            if (!verifyHermes()) throw IllegalStateException("hermes --version 校验失败")
            mark(Constants.HERMES_MARKER)
        } else {
            SetupState.appendLog("Hermes 已安装，跳过")
        }

        if (!webuiReady()) {
            installWebui(context)
        } else {
            SetupState.appendLog("Hermes WebUI 已安装，跳过")
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
        if (!r.ok) throw IllegalStateException("创建 ${Constants.BASE_DIR} 失败: ${r.output}")
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
        if (!r.output.contains("installed")) throw IllegalStateException("部署 proot 失败: ${r.output}")
        stage.deleteRecursively()
    }

    fun installScripts(context: Context) {
        val stage = File(context.filesDir, "scripts-stage").apply { mkdirs() }
        val names = listOf(
            Constants.SCRIPT_RUN, Constants.SCRIPT_INSTALL,
            Constants.SCRIPT_VERIFY, Constants.SCRIPT_WEBUI
        )
        for (name in names) {
            val body = context.assets.open("scripts/$name").bufferedReader().use { it.readText() }
                .replace("@BASE@", Constants.BASE_DIR)
                .replace("@PORT@", Constants.WEBUI_PORT.toString())
                .replace("@CONTROL_PORT@", Constants.CONTROL_PORT.toString())
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
        if (!r.output.contains("installed")) throw IllegalStateException("部署脚本失败: ${r.output}")
        stage.deleteRecursively()
        File(context.filesDir, "skills-stage").deleteRecursively()
    }

    // --------------------------------------------------------------- rootfs

    fun rootfsReady(): Boolean = rootTest("-f ${Constants.ROOTFS_MARKER}")

    fun hermesReady(): Boolean = rootTest("-f ${Constants.HERMES_MARKER}")

    /** Copy a user-picked rootfs archive into the staging area. */
    fun importRootfsFromUri(context: Context, uri: Uri) {
        val target = stagedArchive(context)
        SetupState.phase(SetupState.Phase.DOWNLOADING, "导入本地 rootfs…", 0)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法打开所选文件")
        if (target.length() == 0L) throw IOException("所选文件为空")
        SetupState.appendLog("已导入 ${target.length() / 1_048_576} MB")
    }

    private fun downloadRootfs(context: Context, url: String): File {
        if (url.isBlank()) throw IllegalStateException("rootfs 地址为空，请填写 URL 或选择本地包")
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
        if (code !in 200..299) throw IOException("下载失败 HTTP $code")
        val append = code == HttpURLConnection.HTTP_PARTIAL
        if (!append) downloaded = 0L

        val remaining = connection.contentLengthLong
        val total = if (remaining > 0) downloaded + remaining else -1L
        ensureFreeSpace(context.filesDir, if (total > 0) total else 2L * 1024 * 1024 * 1024)

        SetupState.phase(SetupState.Phase.DOWNLOADING, "下载 rootfs…", if (total > 0) 0 else -1)
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
        if (!r.ok) throw IllegalStateException("解压失败: ${r.output.takeLast(500)}")
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
    private fun runScriptStreaming(scriptPath: String): Int {
        val process = ProcessBuilder("su", "-c", "sh ${ShellUtils.shellQuote(scriptPath)}")
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
            SetupState.phase(SetupState.Phase.DOWNLOADING, "下载 Hermes WebUI…", 0)
            downloadFile(Constants.DEFAULT_WEBUI_URL, archive, "Hermes WebUI", 300L * 1024 * 1024)

            val staged = "${Constants.TMP_DIR}/webui.tar.gz"
            val cp = RootShell.exec(
                "mkdir -p ${Constants.TMP_DIR} && cp -f ${ShellUtils.shellQuote(archive.absolutePath)} " +
                    "$staged && echo ok",
                120_000
            )
            if (!cp.output.contains("ok")) throw IOException("复制 WebUI 包失败: ${cp.output}")
            archive.delete()

            SetupState.phase(SetupState.Phase.INSTALLING, "安装 Hermes WebUI…", -1)
            val rc = runScriptStreaming(scriptPath(Constants.SCRIPT_WEBUI))
            RootShell.exec("rm -f $staged")
            if (rc != 0 || !webuiReady()) {
                SetupState.appendLog("WebUI 安装失败（退出码 $rc），将回退 dashboard")
                return
            }
            SetupState.appendLog("Hermes WebUI 已安装")
        } catch (t: Throwable) {
            SetupState.appendLog("WebUI 安装跳过：${t.message}")
            Log.w(TAG, "webui install failed", t)
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
        if (code !in 200..299) throw IOException("$label 下载失败 HTTP $code")
        val total = connection.contentLengthLong
        ensureFreeSpace(target, minFreeBytes)
        SetupState.phase(SetupState.Phase.DOWNLOADING, "下载 $label…", if (total > 0) 0 else -1)
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
        SetupState.appendLog("$label 下载完成（${target.length() / 1_048_576} MB）")
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
            throw IOException("空间不足：需要约 ${requiredBytes / 1_048_576} MB，可用 ${available / 1_048_576} MB")
        }
    }

    private fun verifyShaIfConfigured(context: Context, file: File) {
        val expected = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString("rootfs_sha256", null)?.trim().orEmpty()
        if (expected.isEmpty()) {
            SetupState.appendLog("未配置 sha256，跳过校验")
            return
        }
        SetupState.phase(SetupState.Phase.DOWNLOADING, "校验 sha256…", -1)
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
            throw IOException("sha256 不匹配：$actual != $expected")
        }
        SetupState.appendLog("sha256 校验通过")
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.setExecutable(true)
    }
}
