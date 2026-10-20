package com.nous.hermes.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the Hermes runtime after boot / app update when the user enabled
 * auto-start and the runtime is already installed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PREF_AUTO_START, true)) return

        val pending = goAsync()
        Thread({
            try {
                if (RootShell.isRootAvailable() && RootfsManager.isReadyQuick()) {
                    ProotService.start(context, ProotService.ACTION_START)
                } else {
                    Log.i(TAG, "runtime not ready, skipping auto-start")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "auto-start failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }, "boot-autostart").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
