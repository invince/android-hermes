package com.nous.hermes.phone

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Persistent `su` shell.
 *
 * Design notes (fixes the v1 bugs):
 *  - one long-lived `su` process => the user authorises root exactly once and
 *    consecutive commands (tap/swipe/...) do not pay process spawn cost;
 *  - stdout and stderr are merged (redirectErrorStream) and read on a worker
 *    thread so a command cannot deadlock on an unread pipe;
 *  - every command ends with a unique sentinel carrying `$?`, so we know both
 *    when the command finished and whether it succeeded;
 *  - on timeout the shell is torn down and lazily restarted (a blocked
 *    readLine cannot be cancelled cleanly).
 */
object RootShell {
    private const val TAG = "RootShell"

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    private val readerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "root-shell-reader").apply { isDaemon = true }
    }

    @Volatile
    private var process: Process? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var reader: BufferedReader? = null

    @Synchronized
    fun exec(command: String, timeoutMs: Long = 20_000): Result {
        return try {
            ensureStarted()
            runCommand(command, timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: ${t.message}", t)
            shutdown()
            Result(-1, "root shell error: ${t.message}", false)
        }
    }

    /** Returns root uid string, or null when su is unavailable/denied. */
    fun isRootAvailable(): Boolean {
        val r = exec("id -u", 15_000)
        return !r.timedOut && r.output.trim().lines().lastOrNull { it.isNotBlank() }?.trim() == "0"
    }

    /** Best-effort cleanup; call when the owning service stops. */
    @Synchronized
    fun close() = shutdown()

    private fun ensureStarted() {
        val existing = process
        if (existing != null && existing.isAlive) return
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
    }

    private fun runCommand(command: String, timeoutMs: Long): Result {
        val w = writer ?: error("shell not started")
        val token = "__HERMES_DONE_${System.nanoTime()}__"

        // Send the command, then a marker printing the exit status.
        w.write(command)
        w.write("\nprintf '$token%s\\n' \"\$?\"\n")
        w.flush()

        val out = StringBuilder()
        val exit = intArrayOf(-1)
        val callable = Callable {
            val r = reader ?: return@Callable
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith(token)) {
                    exit[0] = line.removePrefix(token).trim().toIntOrNull() ?: -1
                    break
                }
                synchronized(out) { out.append(line).append('\n') }
            }
        }
        val future: Future<*> = readerPool.submit(callable)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
            Result(exit[0], synchronized(out) { out.toString() }, false)
        } catch (e: TimeoutException) {
            Log.w(TAG, "command timed out, resetting shell: $command")
            future.cancel(true)
            shutdown()
            Result(-1, synchronized(out) { out.toString() }, true)
        }
    }

    @Synchronized
    private fun shutdown() {
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { process?.destroy() } catch (_: Throwable) {}
        writer = null
        reader = null
        process = null
    }
}
