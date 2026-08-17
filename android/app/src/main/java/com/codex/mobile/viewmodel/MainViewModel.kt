package com.codex.mobile.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codex.mobile.repository.BootstrapRepository
import com.codex.mobile.repository.ServerRepository
import com.codex.mobile.state.SetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the application startup flow: bootstrap extraction, package
 * installation, server launch, and UI state transitions.
 *
 * Uses unidirectional data flow: UI observes [state], calls [onEvent].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val bootstrapRepo = BootstrapRepository(application)
    private val serverRepo = ServerRepository(application)

    init {
        startSetup()
    }

    override fun onCleared() {
        super.onCleared()
        serverRepo.stopServer()
        Log.i(TAG, "ViewModel cleared, server stopped")
    }

    fun onRetry() {
        _state.update { SetupState() }
        startSetup()
    }

    fun onApiKeySubmit(apiKey: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    serverRepo.saveApiKey(apiKey)
                }
                _state.update { it.copy(apiKey = apiKey, phase = SetupState.Phase.STARTING_SERVER) }
                startServer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save API key", e)
                _state.update {
                    it.copy(
                        phase = SetupState.Phase.ERROR,
                        error = "Failed to save API key: ${e.message}",
                    )
                }
            }
        }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun startSetup() {
        viewModelScope.launch {
            try {
                executeSetup()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                _state.update {
                    it.copy(
                        phase = SetupState.Phase.ERROR,
                        error = e.message ?: "Unknown error occurred",
                    )
                }
            }
        }
    }

    private suspend fun executeSetup() {
        // Step 1: Extract bootstrap
        if (!bootstrapRepo.isInstalled()) {
            updatePhase(SetupState.Phase.EXTRACTING_BOOTSTRAP, 0.1f, "Extracting environment…")
            withContext(Dispatchers.IO) {
                bootstrapRepo.install { msg ->
                    updateDetail(msg)
                }
            }
        }
        updatePhase(SetupState.Phase.EXTRACTING_BOOTSTRAP, 0.2f, "Environment ready")

        // Step 2: Install proot
        if (!serverRepo.isProotInstalled()) {
            updatePhase(SetupState.Phase.INSTALLING_PROOT, 0.25f, "Installing proot…", "Needed for package management")
            withContext(Dispatchers.IO) {
                val ok = serverRepo.installProot { msg -> updateDetail(msg) }
                if (!ok) throw RuntimeException("Failed to install proot")
            }
        }
        updatePhase(SetupState.Phase.INSTALLING_PROOT, 0.35f, "proot ready")

        // Step 3: Install Node.js
        if (!serverRepo.isNodeInstalled()) {
            updatePhase(SetupState.Phase.INSTALLING_NODE, 0.4f, "Installing Node.js…", "This may take a few minutes")
            withContext(Dispatchers.IO) {
                val ok = serverRepo.installNode { msg -> updateDetail(msg) }
                if (!ok) throw RuntimeException("Failed to install Node.js")
            }
        }
        updatePhase(SetupState.Phase.INSTALLING_NODE, 0.6f, "Node.js ready")

        // Step 4: Install Codex CLI
        if (!serverRepo.isCodexInstalled()) {
            updatePhase(SetupState.Phase.INSTALLING_CODEX, 0.65f, "Installing Codex CLI…", "This may take a few minutes")
            withContext(Dispatchers.IO) {
                val ok = serverRepo.installCodex { msg -> updateDetail(msg) }
                if (!ok) throw RuntimeException("Failed to install Codex CLI")
            }
        }
        updatePhase(SetupState.Phase.INSTALLING_CODEX, 0.8f, "Codex CLI ready")

        // Step 5: Check API key
        val apiKey = withContext(Dispatchers.IO) { serverRepo.getApiKey() }
        if (apiKey == null) {
            _state.update {
                it.copy(
                    phase = SetupState.Phase.API_KEY_PROMPT,
                    progress = 0.85f,
                    message = "Enter your OpenAI API key",
                )
            }
            return
        }

        _state.update { it.copy(apiKey = apiKey) }

        // Step 6: Start server
        startServer()
    }

    private suspend fun startServer() {
        updatePhase(SetupState.Phase.STARTING_SERVER, 0.9f, "Starting server…")

        withContext(Dispatchers.IO) {
            serverRepo.ensureWorkspace()
            serverRepo.ensureFullAccessConfig()
            serverRepo.startServer()
        }

        updatePhase(SetupState.Phase.WAITING_SERVER, 0.95f, "Waiting for server…")

        withContext(Dispatchers.IO) {
            val ready = serverRepo.waitForServer(maxAttempts = 30, delayMs = 1000)
            if (!ready) throw RuntimeException("Server did not start in time")
        }

        _state.update {
            it.copy(
                phase = SetupState.Phase.RUNNING,
                progress = 1f,
                message = "Ready",
                isComplete = true,
            )
        }
    }

    private fun updatePhase(phase: SetupState.Phase, progress: Float, message: String, detail: String = "") {
        _state.update { it.copy(phase = phase, progress = progress, message = message, detail = detail) }
    }

    private fun updateDetail(detail: String) {
        _state.update { it.copy(detail = detail) }
    }
}
