package com.nous.hermes.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nous.hermes.phone.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadedWebUiUrl: String? = null
    private var installed = false
    private var setupPanelPinned = false

    private val pickRootfs = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRootfs(uri)
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        configureWebView()
        bindActions()
        observeState()
        requestPermissionsIfNeeded()

        binding.rootfsUrl.setText(
            HermesApp.prefs.getString(Constants.PREF_ROOTFS_URL, Constants.DEFAULT_ROOTFS_URL)
        )
        binding.hermesUrl.setText(
            HermesApp.prefs.getString(Constants.PREF_HERMES_URL, Constants.DEFAULT_HERMES_URL)
        )
        binding.webuiUrl.setText(
            HermesApp.prefs.getString(Constants.PREF_WEBUI_URL, Constants.DEFAULT_WEBUI_URL)
        )

        checkExistingInstall()
    }

    /**
     * targetSdk 35 draws edge-to-edge: without insets the system nav bar and
     * the IME cover the WebUI composer (send button becomes unreachable).
     * Pad the root by the larger of nav-bar / IME bottom inset.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun configureWebView() {
        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        binding.webview.webViewClient = WebViewClient()
    }

    private fun bindActions() {
        binding.btnStart.setOnClickListener {
            setupPanelPinned = false
            val url = binding.rootfsUrl.text.toString().trim()
                .ifBlank { Constants.DEFAULT_ROOTFS_URL }
            val hermesUrl = binding.hermesUrl.text.toString().trim()
                .ifBlank { Constants.DEFAULT_HERMES_URL }
            val webuiUrl = binding.webuiUrl.text.toString().trim()
                .ifBlank { Constants.DEFAULT_WEBUI_URL }
            HermesApp.prefs.edit()
                .putString(Constants.PREF_ROOTFS_URL, url)
                .putString(Constants.PREF_HERMES_URL, hermesUrl)
                .putString(Constants.PREF_WEBUI_URL, webuiUrl)
                .apply()
            requestIgnoreBatteryOptimizations()
            SetupState.phase(SetupState.Phase.CHECKING, "启动安装…")
            ProotService.start(this, ProotService.ACTION_SETUP, url)
            binding.btnStart.isEnabled = false
        }

        binding.btnPickRootfs.setOnClickListener {
            pickRootfs.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*"))
        }

        binding.btnOpen.setOnClickListener {
            setupPanelPinned = false
            val url = "http://127.0.0.1:${Constants.WEBUI_PORT}"
            showWebUi(url)
            ProotService.start(this, ProotService.ACTION_START)
        }

        binding.btnUpdateHermes.setOnClickListener { confirmUpdate(hermes = true) }
        binding.btnUpdateWebui.setOnClickListener { confirmUpdate(hermes = false) }
        binding.btnUninstall.setOnClickListener { confirmUninstall() }
        binding.btnMenu.setOnClickListener { showSetupPanel() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.visibility != android.view.View.VISIBLE) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                // WebUI keeps its own history; going back inside it is easy to
                // get stuck in. Back always returns to the local panel, where
                // the user can reopen the WebUI or uninstall.
                showSetupPanel()
            }
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SetupState.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: SetupState.UiState) {
        val label = buildString {
            append(state.phase.name)
            if (state.message.isNotBlank()) append(" · ").append(state.message)
            if (state.error != null) append("\n错误: ").append(state.error)
        }
        binding.statusText.text = label
        binding.logText.text = state.log

        if (state.progress in 0..100) {
            binding.progress.visibility = android.view.View.VISIBLE
            binding.progress.isIndeterminate = false
            binding.progress.progress = state.progress
        } else {
            binding.progress.isIndeterminate =
                state.phase != SetupState.Phase.IDLE && state.phase != SetupState.Phase.READY
            binding.progress.visibility =
                if (state.phase == SetupState.Phase.IDLE || state.phase == SetupState.Phase.READY)
                    android.view.View.INVISIBLE else android.view.View.VISIBLE
        }

        val busy = state.phase == SetupState.Phase.CHECKING ||
            state.phase == SetupState.Phase.DOWNLOADING ||
            state.phase == SetupState.Phase.EXTRACTING ||
            state.phase == SetupState.Phase.INSTALLING ||
            state.phase == SetupState.Phase.VERIFYING ||
            state.phase == SetupState.Phase.STARTING
        binding.btnStart.isEnabled = !busy
        binding.btnStart.text =
            if (state.phase == SetupState.Phase.ERROR) getString(R.string.btn_retry)
            else getString(R.string.btn_start)

        if (state.phase == SetupState.Phase.IDLE && state.message == getString(R.string.uninstall_done)) {
            installed = false
        }
        val installedVisibility =
            if (installed) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnOpen.visibility = installedVisibility
        binding.btnUpdateHermes.visibility = installedVisibility
        binding.btnUpdateWebui.visibility = installedVisibility
        binding.btnUpdateHermes.isEnabled = !busy
        binding.btnUpdateWebui.isEnabled = !busy
        binding.btnUninstall.visibility = installedVisibility
        binding.btnUninstall.isEnabled = !busy

        if (state.ready && !setupPanelPinned) {
            installed = true
            showWebUi("http://127.0.0.1:${Constants.WEBUI_PORT}")
        }
    }

    private fun showSetupPanel() {
        setupPanelPinned = true
        binding.webview.visibility = android.view.View.GONE
        binding.btnMenu.visibility = android.view.View.GONE
        binding.setupPanel.visibility = android.view.View.VISIBLE
    }

    /** Hardware/gesture back: leave the WebUI for the local panel first. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            binding.webview.visibility == android.view.View.VISIBLE
        ) {
            if (event.action == android.view.KeyEvent.ACTION_UP) showSetupPanel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun confirmUpdate(hermes: Boolean) {
        val what = if (hermes) "Hermes" else "WebUI"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, what))
            .setMessage(R.string.update_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> startUpdate(hermes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startUpdate(hermes: Boolean) {
        showSetupPanel()
        SetupState.phase(SetupState.Phase.CHECKING, "准备更新 ${if (hermes) "Hermes" else "WebUI"}…")
        ProotService.start(
            this,
            if (hermes) ProotService.ACTION_UPDATE_HERMES else ProotService.ACTION_UPDATE_WEBUI
        )
    }

    private fun confirmUninstall() {
        val check = android.widget.CheckBox(this).apply {
            text = getString(R.string.uninstall_delete_data)
            setPadding(48, 0, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.uninstall_title)
            .setMessage(R.string.uninstall_message)
            .setView(check)
            .setPositiveButton(R.string.uninstall_confirm) { _, _ ->
                startUninstall(check.isChecked)
            }
            .setNegativeButton(R.string.uninstall_cancel, null)
            .show()
    }

    private fun startUninstall(deleteHome: Boolean) {
        loadedWebUiUrl = null
        binding.btnUninstall.isEnabled = false
        binding.btnStart.isEnabled = false
        showSetupPanel()
        SetupState.phase(SetupState.Phase.CHECKING, getString(R.string.uninstall_running))
        ProotService.start(this, ProotService.ACTION_UNINSTALL, deleteHome = deleteHome)
    }

    private fun showWebUi(url: String) {
        binding.setupPanel.visibility = android.view.View.GONE
        binding.webview.visibility = android.view.View.VISIBLE
        binding.btnMenu.visibility = android.view.View.VISIBLE
        // WebView normalizes "…:8787" to "…:8787/", so comparing against
        // getUrl() would reload on every state emission (and the WebUI logs
        // one line per request, which feeds straight back into setup state).
        if (loadedWebUiUrl != url) {
            loadedWebUiUrl = url
            binding.webview.loadUrl(url)
        }
    }

    private fun checkExistingInstall() {
        binding.btnStart.isEnabled = false
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                RootShell.isRootAvailable() && RootfsManager.isReadyQuick()
            }
            val webuiMissing = ready && withContext(Dispatchers.IO) { !RootfsManager.webuiReady() }
            installed = ready
            if (ready && webuiMissing) {
                // Upgrade path: rootfs+Hermes already present, only install the
                // native WebUI (setup is idempotent and skips the other stages).
                val url = HermesApp.prefs.getString(Constants.PREF_ROOTFS_URL, Constants.DEFAULT_ROOTFS_URL)
                    ?: Constants.DEFAULT_ROOTFS_URL
                SetupState.phase(SetupState.Phase.CHECKING, "安装 Hermes WebUI…")
                ProotService.start(this@MainActivity, ProotService.ACTION_SETUP, url)
            } else if (ready) {
                SetupState.phase(SetupState.Phase.STARTING, "Hermes 已安装，启动中…")
                ProotService.start(this@MainActivity, ProotService.ACTION_START)
            } else {
                SetupState.phase(SetupState.Phase.IDLE, "尚未安装，点击下方开始")
                binding.btnStart.isEnabled = true
            }
        }
    }

    private fun importRootfs(uri: Uri) {
        binding.btnStart.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RootfsManager.importRootfsFromUri(this@MainActivity, uri) }
                SetupState.phase(SetupState.Phase.IDLE, "rootfs 已导入，点击开始安装")
                binding.btnStart.isEnabled = true
            } catch (t: Throwable) {
                SetupState.fail("导入失败", t)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = getSystemService(android.os.PowerManager::class.java)
        if (manager.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
            // Some OEM ROMs don't expose this intent; ignore.
        }
    }
}
