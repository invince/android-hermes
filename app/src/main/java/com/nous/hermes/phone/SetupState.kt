package com.nous.hermes.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process observable state for the onboarding / runtime pipeline. Updated by
 * [RootfsManager] and [ProotService], collected by [MainActivity].
 */
object SetupState {

    enum class Phase { IDLE, CHECKING, DOWNLOADING, EXTRACTING, INSTALLING, VERIFYING, STARTING, READY, ERROR }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val progress: Int = -1,       // 0..100, -1 = indeterminate
        val log: String = "",
        val ready: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun phase(phase: Phase, message: String, progress: Int = -1) =
        _state.update { it.copy(phase = phase, message = message, progress = progress, error = null) }

    fun progress(percent: Int) =
        _state.update { it.copy(progress = percent.coerceIn(0, 100)) }

    fun ready(webuiUrl: String) =
        _state.update { it.copy(phase = Phase.READY, ready = true, message = webuiUrl, error = null) }

    fun reset() = _state.update { UiState() }

    fun fail(message: String, error: Throwable? = null) {
        val detail = error?.message?.let { " ($it)" } ?: ""
        _state.update { it.copy(phase = Phase.ERROR, error = message + detail, message = message) }
    }

    fun appendLog(line: String) {
        _state.update {
            val next = (it.log + line + '\n')
            it.copy(log = if (next.length > 20_000) next.takeLast(20_000) else next)
        }
    }
}
