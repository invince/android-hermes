package com.nous.hermes.phone

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Loopback control API for the agent running inside the rootfs:
 *   curl -H "X-Hermes-Token: $(cat /root/.hermes/control-token)" \
 *        "http://127.0.0.1:8788/tap?x=540&y=960"
 *
 * This is the seam that turns Hermes' terminal tool into the phone's "hands".
 * Bound to 127.0.0.1 only and gated by a random token (chmod 600, written into
 * hermes-home which the rootfs sees as /root/.hermes).
 */
class ControlServer(private val port: Int, private val token: String) {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "control-server").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        executor.execute { acceptLoop() }
        Log.i(TAG, "control server listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        try {
            ServerSocket(port, 8, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                serverSocket = server
                while (running) {
                    val socket = try { server.accept() } catch (e: IOException) { break }
                    executor.execute { handle(socket) }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "control server stopped: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return respond(s.getOutputStream(), 400, jsonError("bad request"))

            var contentLength = 0
            var providedToken: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "x-hermes-token" -> providedToken = value
                    }
                }
            }
            val body = if (contentLength in 1..1_000_000) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val path = parts[1]
            val route = path.substringBefore('?')
            val query = parseQuery(path.substringAfter('?', ""))
            val requestToken = providedToken ?: query["token"]

            if (!constantTimeEquals(token, requestToken)) {
                return respond(s.getOutputStream(), 401, jsonError("unauthorized"))
            }

            val result: String = try {
                dispatch(route, query, body)
            } catch (t: Throwable) {
                jsonError(t.message ?: t.javaClass.simpleName)
            }
            respond(s.getOutputStream(), 200, result)
        }
    }

    private fun dispatch(route: String, q: Map<String, String>, body: String): String = when (route) {
        "/health" -> """{"ok":true}"""
        "/screen" -> {
            val s = PhoneControl.screenSize()
            """{"ok":true,"width":${s.width},"height":${s.height}}"""
        }
        "/tap" -> ok(PhoneControl.tap(q.int("x"), q.int("y")).output)
        "/longpress" -> ok(PhoneControl.longPress(q.int("x"), q.int("y"), q["ms"]?.toIntOrNull() ?: 800).output)
        "/swipe" -> ok(
            PhoneControl.swipe(
                q.int("x1"), q.int("y1"), q.int("x2"), q.int("y2"), q["ms"]?.toIntOrNull() ?: 300
            ).output
        )
        "/text" -> ok(PhoneControl.text(q["text"] ?: body).output)
        "/key" -> ok(PhoneControl.keyEvent(q["code"] ?: error("code required")).output)
        "/screenshot" -> {
            val shot = PhoneControl.screenshot()
            """{"ok":true,"host":"${jsonEscape(shot.hostPath)}","guest":"${jsonEscape(shot.guestPath)}"}"""
        }
        "/launch" -> ok(PhoneControl.launch(q["component"] ?: error("component required")).output)
        "/open" -> ok(PhoneControl.openUrl(q["url"] ?: error("url required")).output)
        "/settings" -> ok(
            PhoneControl.setting(q["ns"] ?: "global", q["k"] ?: error("k required"), q["v"].orEmpty()).output
        )
        "/logcat" -> ok(PhoneControl.logcat(q["n"]?.toIntOrNull() ?: 50).output)
        "/battery" -> ok(PhoneControl.battery().output)
        else -> """{"ok":false,"error":"unknown route: ${jsonEscape(route)}"}"""
    }

    private fun ok(output: String) = """{"ok":true,"output":"${jsonEscape(output)}"}"""
    private fun jsonError(message: String) = """{"ok":false,"error":"${jsonEscape(message)}"}"""

    private fun respond(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else
                URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun Map<String, String>.int(key: String): Int =
        this[key]?.toIntOrNull() ?: error("$key required")

    companion object {
        private const val TAG = "ControlServer"

        /** Generates (once) and writes the control token into hermes-home. */
        fun ensureToken(context: Context): String {
            val prefs = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            var token = prefs.getString("control_token", null)
            if (token.isNullOrBlank()) {
                val bytes = ByteArray(16)
                SecureRandom().nextBytes(bytes)
                token = bytes.joinToString("") { "%02x".format(it) }
                prefs.edit().putString("control_token", token).apply()
            }
            val r = RootShell.exec(
                "printf %s ${ShellUtils.shellQuote(token)} > ${Constants.HERMES_HOME}/control-token && " +
                    "chmod 600 ${Constants.HERMES_HOME}/control-token && echo ok"
            )
            if (!r.output.contains("ok")) Log.w(TAG, "failed to write control token: ${r.output}")
            return token
        }

        fun jsonEscape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            return sb.toString()
        }
    }
}
