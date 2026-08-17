package com.codex.mobile.state

import com.codex.mobile.BootstrapInstaller

/**
 * Represents the complete UI state of the application startup flow.
 * Immutable data class ensuring thread-safe state updates via StateFlow.
 */
data class SetupState(
    val phase: Phase = Phase.INITIALIZING,
    val progress: Float = 0f,
    val message: String = "",
    val detail: String = "",
    val error: String? = null,
    val isComplete: Boolean = false,
    val apiKey: String? = null,
) {
    enum class Phase(val label: String) {
        INITIALIZING("Initializing"),
        EXTRACTING_BOOTSTRAP("Extracting environment"),
        INSTALLING_PROOT("Installing proot"),
        INSTALLING_NODE("Installing Node.js"),
        INSTALLING_CODEX("Installing Codex CLI"),
        STARTING_SERVER("Starting server"),
        WAITING_SERVER("Waiting for server"),
        API_KEY_PROMPT("API key required"),
        RUNNING("Running"),
        ERROR("Error"),
    }

    val isLoading: Boolean
        get() = phase != Phase.ERROR && phase != Phase.RUNNING && !isComplete

    val progressPercent: Int
        get() = (progress * 100).toInt().coerceIn(0, 100)
}
