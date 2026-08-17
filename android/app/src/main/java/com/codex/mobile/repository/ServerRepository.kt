package com.codex.mobile.repository

import android.content.Context
import android.util.Log
import com.codex.mobile.state.BootstrapInstaller
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the Node.js Codex server lifecycle: package installation,
 * process management, health checks, and configuration.
 */
class ServerRepository(private val context: Context) {

    companion object {
        private const val TAG = "ServerRepository"
        const val SERVER_PORT = 18923
        private const val PROXY_PORT = 18924
        private const val CODEX_VERSION = "0.104.0"
        const val OPENCLAW_GATEWAY_PORT = 18789
        const val OPENCLAW_CONTROL_UI_PORT = 19001
    }

    private var serverProcess: Process? = null
    private var proxyProcess: Process? = null

    val isRunning: Boolean
        get() {
            val proc = serverProcess ?: return false
            return try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }

    // ── Package checks ───────────────────────────────────────────────────

    fun isProotInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/proot").exists()
    }

    fun isNodeInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/node").exists()
    }

    fun isCodexInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/codex").exists()
    }

    // ── Package installation ──────────────────────────────────────────────

    fun installProot(onOutput: (String) -> Unit): Boolean {
        val code = runInPrefix("apt-get update -y && apt-get install -y proot", onOutput)
        return code == 0
    }

    fun installNode(onOutput: (String) -> Unit): Boolean {
        val code = runInPrefix("apt-get install -y nodejs-lts", onOutput)
        return code == 0
    }

    fun installCodex(onOutput: (String) -> Unit): Boolean {
        val code = runInPrefix("npm install -g @openai/codex codex-web-local", onOutput)
        return code == 0
    }

    // ── Server lifecycle ──────────────────────────────────────────────────

    fun startServer() {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        // Start the codex-web-local server
        val serverCmd = listOf(
            "${paths.prefixDir}/bin/node",
            "${paths.prefixDir}/lib/node_modules/codex-web-local/dist-cli/server.js",
            "--port", SERVER_PORT.toString(),
        )
        serverProcess = startProcess(serverCmd, env, paths)

        // Start the HTTP proxy
        val proxyCmd = listOf(
            "${paths.prefixDir}/bin/node",
            "${paths.prefixDir}/lib/node_modules/codex-web-local/dist-cli/proxy.js",
            "--port", PROXY_PORT.toString(),
        )
        proxyProcess = startProcess(proxyCmd, env, paths)

        Log.i(TAG, "Server processes started")
    }

    fun stopServer() {
        serverProcess?.destroy()
        proxyProcess?.destroy()
        serverProcess = null
        proxyProcess = null
        Log.i(TAG, "Server processes stopped")
    }

    fun waitForServer(maxAttempts: Int = 30, delayMs: Long = 1000): Boolean {
        repeat(maxAttempts) { attempt ->
            try {
                val url = URL("http://127.0.0.1:$SERVER_PORT/")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                if (conn.responseCode in 200..399) {
                    Log.i(TAG, "Server ready after ${attempt + 1} attempts")
                    return true
                }
            } catch (_: Exception) {
                // Server not ready yet
            }
            Thread.sleep(delayMs)
        }
        return false
    }

    // ── Configuration ─────────────────────────────────────────────────────

    fun getApiKey(): String? {
        val paths = BootstrapInstaller.getPaths(context)
        val configFile = File(paths.homeDir, ".codex/config.toml")
        if (!configFile.exists()) return null
        val content = configFile.readText()
        val match = Regex("""api_key\s*=\s*["'](.+?)["']""").find(content)
        return match?.groupValues?.get(1)
    }

    fun saveApiKey(apiKey: String) {
        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()
        val configFile = File(configDir, "config.toml")
        configFile.writeText("""
            |api_key = "$apiKey"
            |approval_policy = "never"
            |sandbox_mode = "danger-full-access"
        """.trimMargin().trim() + "\n")
        Log.i(TAG, "API key saved")
    }

    fun ensureWorkspace() {
        val paths = BootstrapInstaller.getPaths(context)
        val workspaceDir = File(paths.homeDir, "workspace")
        workspaceDir.mkdirs()
        runInPrefix("cd ${workspaceDir.absolutePath} && git init 2>&1")
        Log.i(TAG, "Workspace ensured at ${workspaceDir.absolutePath}")
    }

    fun ensureFullAccessConfig() {
        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()
        val configFile = File(configDir, "config.toml")
        val desired = """
            |approval_policy = "never"
            |sandbox_mode = "danger-full-access"
        """.trimMargin().trim() + "\n"

        if (configFile.exists()) {
            val current = configFile.readText()
            if (current.contains("approval_policy") && current.contains("danger-full-access")) {
                return
            }
        }
        configFile.writeText(desired)
        Log.i(TAG, "Full-access config written")
    }

    // ── Shell helpers ─────────────────────────────────────────────────────

    private fun runInPrefix(
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    private fun startProcess(
        command: List<String>,
        env: Map<String, String>,
        paths: BootstrapInstaller.Paths,
    ): Process {
        val pb = ProcessBuilder(command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    private fun buildEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/.openclaw-android/patches/bionic-compat.js"
        val bionicCompatOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""

        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TMP" to paths.tmpDir,
            "TEMP" to paths.tmpDir,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_EXEC_PATH" to "${paths.prefixDir}/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "${paths.prefixDir}/share/git-core/templates",
            "OPENSSL_CONF" to "${paths.prefixDir}/etc/tls/openssl.cnf",
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=warn$bionicCompatOpt",
            "CONTAINER" to "1",
        )
    }
}
