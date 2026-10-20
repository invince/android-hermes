package com.nous.hermes.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground service that (1) performs onboarding ([ACTION_SETUP]) and
 * (2) supervises the long-running Hermes dashboard/gateway inside proot.
 *
 * v1 fix: the gateway is a never-ending process, so it is started on a worker
 * thread and its output is drained line-by-line — never `readText()` on the
 * main thread (ANR).
 */
class ProotService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hermes-worker").apply { isDaemon = true }
    }

    @Volatile private var gatewayProcess: Process? = null
    @Volatile private var controlServer: ControlServer? = null
    @Volatile private var gatewayStarted = false
    @Volatile private var healthGeneration = 0
    @Volatile private var uninstalling = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        startForeground(Constants.NOTIF_ID, buildNotification(getString(R.string.notif_title), -1))

        when (action) {
            ACTION_SETUP -> {
                val url = intent?.getStringExtra(EXTRA_ROOTFS_URL).orEmpty()
                worker.execute { runSetup(url) }
            }
            ACTION_START -> worker.execute { startGateway() }
            ACTION_UNINSTALL -> {
                val deleteHome = intent?.getBooleanExtra(EXTRA_DELETE_HOME, false) ?: false
                // Do not queue behind the 90s health wait in `worker`.
                Thread({ runUninstall(deleteHome) }, "hermes-uninstall").start()
            }
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
        worker.shutdownNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --------------------------------------------------------------- setup

    private fun runSetup(rootfsUrl: String) {
        try {
            updateNotification(getString(R.string.notif_setup), -1)
            RootfsManager.setup(applicationContext, rootfsUrl)
            startGateway()
        } catch (t: Throwable) {
            Log.e(TAG, "setup failed", t)
            SetupState.fail("安装失败", t)
            updateNotification("安装失败：${t.message}", -1)
        }
    }

    private fun runUninstall(deleteHome: Boolean) {
        uninstalling = true
        try {
            updateNotification(getString(R.string.uninstall_running), -1)
            stopEverything()
            RootfsManager.uninstall(applicationContext, deleteHome)
            SetupState.reset()
            SetupState.phase(SetupState.Phase.IDLE, getString(R.string.uninstall_done))
        } catch (t: Throwable) {
            Log.e(TAG, "uninstall failed", t)
            SetupState.fail("卸载失败", t)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------- gateway

    @Synchronized
    private fun startGateway() {
        if (uninstalling) {
            SetupState.appendLog("正在卸载，忽略启动请求")
            return
        }
        if (gatewayStarted) {
            SetupState.appendLog("gateway 已在运行")
            markReadyWhenHealthy()
            return
        }
        try {
            if (!RootfsManager.isReadyQuick()) {
                throw IllegalStateException("Hermes 未安装完成，无法启动")
            }
            // Refresh launcher scripts after an app update (cheap, idempotent).
            try { RootfsManager.installScripts(applicationContext) } catch (t: Throwable) {
                Log.w(TAG, "script refresh failed: ${t.message}")
            }
            val token = ControlServer.ensureToken(applicationContext)
            controlServer = ControlServer(Constants.CONTROL_PORT, token).also { it.start() }

            val script = "${Constants.SCRIPTS_DIR}/${Constants.SCRIPT_RUN}"
            SetupState.phase(SetupState.Phase.STARTING, "启动 Hermes…", -1)
            val process = ProcessBuilder("su", "-c", "sh ${ShellUtils.shellQuote(script)}")
                .redirectErrorStream(true)
                .start()
            gatewayProcess = process
            gatewayStarted = true

            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, line)
                        SetupState.appendLog(line)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "gateway output ended: ${t.message}")
                }
                SetupState.appendLog("gateway 进程结束")
                gatewayStarted = false
            }, "hermes-gateway-log").apply { isDaemon = true }.start()

            markReadyWhenHealthy()
        } catch (t: Throwable) {
            Log.e(TAG, "gateway start failed", t)
            SetupState.fail("启动失败", t)
            updateNotification("启动失败：${t.message}", -1)
        }
    }

    private fun markReadyWhenHealthy() {
        val generation = ++healthGeneration
        worker.execute {
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && generation == healthGeneration) {
                if (webUiHealthy()) {
                    val url = "http://127.0.0.1:${Constants.WEBUI_PORT}"
                    SetupState.ready(url)
                    updateNotification(getString(R.string.notif_title), -1)
                    return@execute
                }
                Thread.sleep(1500)
            }
            if (generation != healthGeneration) return@execute
            SetupState.fail("WebUI 超时未就绪")
            updateNotification("WebUI 超时未就绪", -1)
        }
    }

    private fun webUiHealthy(): Boolean {
        return try {
            val conn = (URL("http://127.0.0.1:${Constants.WEBUI_PORT}/health").openConnection() as HttpURLConnection)
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Throwable) {
            false
        }
    }

    private fun stopEverything() {
        healthGeneration++
        try { gatewayProcess?.destroy() } catch (_: Throwable) {}
        gatewayProcess = null
        gatewayStarted = false
        controlServer?.stop()
        controlServer = null
        RootShell.close()
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIF_CHANNEL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_hermes)
            .setOngoing(true)
            .setContentIntent(pending)
            .apply { if (progress in 0..100) setProgress(100, progress, false) else setProgress(0, 0, true) }
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(Constants.NOTIF_ID, buildNotification(text, progress))
    }

    companion object {
        private const val TAG = "ProotService"

        const val ACTION_SETUP = "com.nous.hermes.phone.action.SETUP"
        const val ACTION_START = "com.nous.hermes.phone.action.START"
        const val ACTION_STOP = "com.nous.hermes.phone.action.STOP"
        const val ACTION_UNINSTALL = "com.nous.hermes.phone.action.UNINSTALL"
        const val EXTRA_ROOTFS_URL = "rootfs_url"
        const val EXTRA_DELETE_HOME = "delete_home"

        fun start(
            context: Context,
            action: String,
            rootfsUrl: String? = null,
            deleteHome: Boolean = false
        ) {
            val intent = Intent(context, ProotService::class.java).setAction(action)
            if (rootfsUrl != null) intent.putExtra(EXTRA_ROOTFS_URL, rootfsUrl)
            if (deleteHome) intent.putExtra(EXTRA_DELETE_HOME, true)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
