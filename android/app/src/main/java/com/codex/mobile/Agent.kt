package com.codex.mobile

import androidx.annotation.ColorRes

/**
 * A selectable coding agent shown in the agent list.
 * [bundled] means the runtime for this agent ships inside this APK build;
 * [webUrl] is the local UI served by the agent's server (only used once
 * the server is actually running).
 * [installCommand] is the shell command that installs the runtime in the
 * bundled environment (Termux/proot); null means it is not installable yet.
 */
data class Agent(
    val id: String,
    val name: String,
    val tagline: String,
    val category: String,
    @ColorRes val colorRes: Int,
    val bundled: Boolean,
    val webUrl: String?,
    val installCommand: String? = null,
)

object AgentCatalog {

    val all: List<Agent> = listOf(
        Agent(
            id = "codex",
            name = "Codex",
            tagline = "OpenAI's official coding agent",
            category = "OpenAI",
            colorRes = R.color.agent_codex,
            bundled = true,
            webUrl = "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/",
        ),
        Agent(
            id = "openclaw",
            name = "OpenClaw",
            tagline = "Autonomous multi-agent gateway + Control UI",
            category = "Open Source",
            colorRes = R.color.agent_openclaw,
            bundled = true,
            webUrl = "http://127.0.0.1:${CodexServerManager.OPENCLAW_CONTROL_UI_PORT}/",
        ),
        Agent(
            id = "opencode",
            name = "OpenCode",
            tagline = "Open-source terminal coding agent",
            category = "Open Source",
            colorRes = R.color.agent_opencode,
            bundled = false,
            webUrl = null,
            installCommand = "npm install -g opencode-ai",
        ),
        Agent(
            id = "hermes",
            name = "Hermes",
            tagline = "On-device reasoning assistant agent",
            category = "Nous Research",
            colorRes = R.color.agent_hermes,
            bundled = false,
            webUrl = null,
        ),
        Agent(
            id = "claude",
            name = "Claude Code",
            tagline = "Anthropic's terminal coding agent",
            category = "Anthropic",
            colorRes = R.color.agent_claude,
            bundled = false,
            webUrl = null,
            installCommand = "npm install -g @anthropic-ai/claude-code",
        ),
        Agent(
            id = "gemini",
            name = "Gemini CLI",
            tagline = "Google's coding agent for the terminal",
            category = "Google",
            colorRes = R.color.agent_gemini,
            bundled = false,
            webUrl = null,
            installCommand = "npm install -g @google/gemini-cli",
        ),
        Agent(
            id = "qwen",
            name = "Qwen Code",
            tagline = "Alibaba's open-source coding agent",
            category = "Alibaba",
            colorRes = R.color.agent_qwen,
            bundled = false,
            webUrl = null,
            installCommand = "pip install -U qwen-code",
        ),
        Agent(
            id = "aider",
            name = "Aider",
            tagline = "AI pair programming in your terminal",
            category = "Open Source",
            colorRes = R.color.agent_aider,
            bundled = false,
            webUrl = null,
            installCommand = "python -m pip install -U aider-chat",
        ),
        Agent(
            id = "goose",
            name = "Goose",
            tagline = "Extensible coding agent by Block",
            category = "Open Source",
            colorRes = R.color.agent_goose,
            bundled = false,
            webUrl = null,
            installCommand = "curl -fsSL https://github.com/block/goose/releases/latest/download/install.sh | bash",
        ),
        Agent(
            id = "continue",
            name = "Continue",
            tagline = "Open-source AI code assistant",
            category = "Open Source",
            colorRes = R.color.agent_continue,
            bundled = false,
            webUrl = null,
        ),
    )

    fun byId(id: String): Agent =
        all.firstOrNull { it.id == id } ?: all.first()
}
