package com.nous.hermes.phone

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nous.hermes.phone.databinding.ActivityTerminalBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal in-app command line that runs inside the Hermes proot.
 *
 * Commands are executed one at a time; [TerminalRunner] echoes the working
 * directory back so `cd` carries over. Interactive full-screen programs (bare
 * `hermes`, `vim`, …) have no TTY here — use the Stop button to abort them.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val buffer = StringBuilder()
    private var cwd = TerminalRunner.defaultCwd()
    private var process: Process? = null
    private var running = false
    private val history = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.terminalInput.setOnEditorActionListener { _, actionId, event ->
            val send = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (send) {
                runCommand(binding.terminalInput.text.toString())
                true
            } else {
                false
            }
        }
        binding.terminalInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> recallHistory(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> recallHistory(1)
                else -> return@setOnKeyListener false
            }
            true
        }
        binding.btnTerminalRun.setOnClickListener {
            runCommand(binding.terminalInput.text.toString())
        }
        binding.btnTerminalStop.setOnClickListener { stopCommand() }
        binding.btnTerminalClear.setOnClickListener {
            buffer.setLength(0)
            render()
        }

        appendLog("Hermes command line ready (root@hermes). Type a command and press Enter; use Stop to exit interactive programs.\n")
        updateStatus()
        binding.terminalInput.requestFocus()
    }

    private fun runCommand(raw: String) {
        if (running) return
        val command = raw.trim()
        if (command.isEmpty()) return
        binding.terminalInput.setText("")
        history.add(command)
        historyIndex = history.size

        when (command) {
            "clear" -> {
                buffer.setLength(0)
                render()
                return
            }
            "exit", "logout" -> {
                finish()
                return
            }
        }

        appendLog("$cwd # $command\n")
        running = true
        updateStatus()

        val proc = try {
            TerminalRunner.start(command, cwd)
        } catch (t: Throwable) {
            appendLog("Failed to start command: ${t.message}\n")
            running = false
            updateStatus()
            return
        }
        process = proc

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith(TerminalRunner.CWD_MARKER)) {
                            cwd = line.removePrefix(TerminalRunner.CWD_MARKER).trim()
                                .ifBlank { cwd }
                            continue
                        }
                        withContext(Dispatchers.Main) { appendLog(stripAnsi(line) + "\n") }
                    }
                }
                val code = proc.waitFor()
                withContext(Dispatchers.Main) {
                    if (code != 0) appendLog("[exit code $code]\n")
                    finishRun()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendLog("Command interrupted: ${t.message}\n")
                    finishRun()
                }
            }
        }
    }

    private fun finishRun() {
        running = false
        process = null
        updateStatus()
    }

    private fun stopCommand() {
        val proc = process ?: return
        appendLog("^C stopping…\n")
        try { proc.destroy() } catch (_: Throwable) {}
        try { proc.destroyForcibly() } catch (_: Throwable) {}
        process = null
        running = false
        updateStatus()
    }

    private fun updateStatus() {
        binding.terminalStatus.text = getString(
            if (running) R.string.terminal_running else R.string.terminal_ready,
            cwd
        )
        binding.btnTerminalStop.isEnabled = running
        binding.btnTerminalRun.isEnabled = !running
        binding.terminalInput.isEnabled = true
    }

    private fun appendLog(text: String) {
        buffer.append(text)
        if (buffer.length > MAX_BUFFER) buffer.delete(0, buffer.length - MAX_BUFFER)
        render()
    }

    private fun render() {
        binding.terminalOutput.text = buffer.toString()
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun recallHistory(direction: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, history.size)
        binding.terminalInput.setText(if (historyIndex == history.size) "" else history[historyIndex])
        binding.terminalInput.setSelection(binding.terminalInput.text.length)
    }

    private fun stripAnsi(value: String): String =
        value.replace(ANSI, "")

    override fun onDestroy() {
        process?.let {
            try { it.destroy() } catch (_: Throwable) {}
            try { it.destroyForcibly() } catch (_: Throwable) {}
        }
        process = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_BUFFER = 200_000
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }
}
