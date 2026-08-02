package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * AnyClaw launcher: a native menu UI (Home / Agents / Settings) that lets the
 * user pick which agent to run. The bundled agent servers (Codex, OpenClaw)
 * are started in the background; the WebView is only ever shown once the
 * selected agent's server is actually running.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AnyClawMain"
        private const val PREFS = "anyclaw_prefs"
        private const val KEY_ACTIVE_AGENT = "active_agent"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_OPENCLAW_DECIDED = "openclaw_decided"
        private const val KEY_OPENCLAW_OPT_IN = "openclaw_opt_in"
        private const val KEY_LAST_SEEN_VERSION = "last_seen_version"

        const val SCREEN_HOME = 0
        const val SCREEN_AGENTS = 1
        const val SCREEN_SETTINGS = 2
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var serverManager: CodexServerManager
    private val sshManager by lazy { SshManager(this) }

    // Screens
    private lateinit var screenHome: View
    private lateinit var screenAgents: View
    private lateinit var screenSettings: View

    // Bottom navigation
    private lateinit var bottomNav: View
    private lateinit var navHome: View
    private lateinit var navAgents: View
    private lateinit var navSettings: View
    private lateinit var navHomeIcon: ImageView
    private lateinit var navAgentsIcon: ImageView
    private lateinit var navSettingsIcon: ImageView
    private lateinit var navHomeLabel: TextView
    private lateinit var navAgentsLabel: TextView
    private lateinit var navSettingsLabel: TextView

    // Home
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var statusHint: TextView
    private lateinit var statusActionBtn: TextView
    private lateinit var setupProgress: ProgressBar
    private lateinit var agentAvatar: TextView
    private lateinit var agentName: TextView
    private lateinit var agentTagline: TextView
    private lateinit var agentRuntimeHint: TextView
    private lateinit var agentStartBtn: TextView
    private lateinit var agentStopBtn: TextView
    private lateinit var buildTag: TextView
    private lateinit var sshStatusText: TextView
    private lateinit var sshStartBtn: TextView
    private lateinit var sshPasswordBtn: TextView
    private lateinit var sshConnectionText: TextView

    // Agents
    private lateinit var agentList: RecyclerView
    private var agentAdapter: AgentAdapter? = null

    // Settings
    private lateinit var loginStatusText: TextView
    private lateinit var serverStatusText: TextView
    private lateinit var versionText: TextView

    // WebView (agent UI — only shown when the server is running)
    private lateinit var agentWebView: WebView
    private lateinit var webMenuBtn: View

    @Volatile private var setupRunning = false
    @Volatile private var openclawUiStarted = false
    @Volatile private var cachedLoggedIn: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        serverManager = CodexServerManager(this)

        setupNavigation()
        setupSettings()
        setupAgentList()
        setupWebView()
        updateActiveAgentCard()
        updateSettingsRows()
        setupVersion()
        announceNewBuild()

        requestBatteryOptimizationExemption()
        startForegroundService()

        showScreen(SCREEN_HOME)

        if (prefs.getBoolean(KEY_SETUP_DONE, false)) {
            // Environment already prepared: make sure the runtime is alive.
            statusTitle.text = getString(R.string.home_status_ready)
            statusDetail.text = getString(R.string.home_status_detail_ready)
            setStatusUi(settingUp = false, ok = true)
            refreshAgentButtons()
        } else {
            startSetup()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverManager.stopServer()
        stopService(Intent(this, CodexForegroundService::class.java))
        try {
            agentWebView.destroy()
        } catch (_: Exception) {
        }
    }

    override fun onBackPressed() {
        if (agentWebView.visibility == View.VISIBLE) {
            hideAgentWebUi()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ── View binding ───────────────────────────────────────────────────────

    private fun bindViews() {
        screenHome = findViewById(R.id.screenHome)
        screenAgents = findViewById(R.id.screenAgents)
        screenSettings = findViewById(R.id.screenSettings)

        bottomNav = findViewById(R.id.bottomNav)
        navHome = findViewById(R.id.navHome)
        navAgents = findViewById(R.id.navAgents)
        navSettings = findViewById(R.id.navSettings)
        navHomeIcon = findViewById(R.id.navHomeIcon)
        navAgentsIcon = findViewById(R.id.navAgentsIcon)
        navSettingsIcon = findViewById(R.id.navSettingsIcon)
        navHomeLabel = findViewById(R.id.navHomeLabel)
        navAgentsLabel = findViewById(R.id.navAgentsLabel)
        navSettingsLabel = findViewById(R.id.navSettingsLabel)

        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        statusDot = findViewById(R.id.statusDot)
        statusHint = findViewById(R.id.statusHint)
        statusActionBtn = findViewById(R.id.statusActionBtn)
        setupProgress = findViewById(R.id.setupProgress)
        agentAvatar = findViewById(R.id.agentAvatar)
        agentName = findViewById(R.id.agentName)
        agentTagline = findViewById(R.id.agentTagline)
        agentRuntimeHint = findViewById(R.id.agentRuntimeHint)
        agentStartBtn = findViewById(R.id.agentStartBtn)
        agentStopBtn = findViewById(R.id.agentStopBtn)
        buildTag = findViewById(R.id.buildTag)
        sshStatusText = findViewById(R.id.sshStatusText)
        sshStartBtn = findViewById(R.id.sshStartBtn)
        sshPasswordBtn = findViewById(R.id.sshPasswordBtn)
        sshConnectionText = findViewById(R.id.sshConnectionText)

        agentList = findViewById(R.id.agentList)

        loginStatusText = findInRow(R.id.rowLogin, R.id.rowValue)
        serverStatusText = findInRow(R.id.rowServer, R.id.rowValue)
        versionText = findInRow(R.id.rowVersion, R.id.rowValue)

        agentWebView = findViewById(R.id.agentWebView)
        webMenuBtn = findViewById(R.id.webMenuBtn)
    }

    /**
     * Finds a view inside a row included from [R.layout.view_setting_row].
     * Uses ViewGroup.findViewById so it works on API 24+ and keeps the
     * (shared) child ids scoped to the right row.
     */
    private fun <T : View> findInRow(rowId: Int, childId: Int): T {
        @Suppress("UNCHECKED_CAST")
        return (findViewById<View>(rowId) as android.view.ViewGroup).findViewById(childId) as T
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun setupNavigation() {
        navHome.setOnClickListener { showScreen(SCREEN_HOME) }
        navAgents.setOnClickListener { showScreen(SCREEN_AGENTS) }
        navSettings.setOnClickListener { showScreen(SCREEN_SETTINGS) }

        findViewById<View>(R.id.activeAgentCard).setOnClickListener {
            showScreen(SCREEN_AGENTS)
        }
        findViewById<View>(R.id.quickAgentsCard).setOnClickListener {
            showScreen(SCREEN_AGENTS)
        }
        findViewById<View>(R.id.repairCard).setOnClickListener {
            confirmRepair()
        }
        findViewById<View>(R.id.terminalCard).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<View>(R.id.logcatCard).setOnClickListener {
            startActivity(Intent(this, LogcatActivity::class.java))
        }
        sshStartBtn.setOnClickListener { toggleSsh() }
        sshPasswordBtn.setOnClickListener { promptSshPassword() }
        findViewById<View>(R.id.statusActionBtn).setOnClickListener {
            confirmRepair()
        }
        agentStartBtn.setOnClickListener { launchActiveAgent() }
        agentStopBtn.setOnClickListener { stopActiveAgent() }
        webMenuBtn.setOnClickListener { hideAgentWebUi() }
    }

    override fun onResume() {
        super.onResume()
        refreshSshCard()
    }

    private fun showScreen(screen: Int) {
        screenHome.visibility = if (screen == SCREEN_HOME) View.VISIBLE else View.GONE
        screenAgents.visibility = if (screen == SCREEN_AGENTS) View.VISIBLE else View.GONE
        screenSettings.visibility = if (screen == SCREEN_SETTINGS) View.VISIBLE else View.GONE

        val activeIcon: ImageView
        val activeLabel: TextView
        when (screen) {
            SCREEN_HOME -> {
                activeIcon = navHomeIcon
                activeLabel = navHomeLabel
            }
            SCREEN_AGENTS -> {
                activeIcon = navAgentsIcon
                activeLabel = navAgentsLabel
            }
            else -> {
                activeIcon = navSettingsIcon
                activeLabel = navSettingsLabel
            }
        }
        setNavState(navHomeIcon, navHomeLabel, active = screen == SCREEN_HOME)
        setNavState(navAgentsIcon, navAgentsLabel, active = screen == SCREEN_AGENTS)
        setNavState(navSettingsIcon, navSettingsLabel, active = screen == SCREEN_SETTINGS)
        updateSettingsRows()
    }

    private fun setNavState(icon: ImageView, label: TextView, active: Boolean) {
        val color = if (active) {
            ContextCompat.getColor(this, R.color.accent)
        } else {
            ContextCompat.getColor(this, R.color.text_faint)
        }
        icon.drawable.mutate().setTint(color)
        label.setTextColor(color)
    }

    private fun setupSettings() {
        val login = findViewById<View>(R.id.rowLogin)
        login.setOnClickListener { startLoginFlow() }
        findInRow<ImageView>(R.id.rowLogin, R.id.rowIcon).setImageResource(R.drawable.ic_account)
        findInRow<TextView>(R.id.rowLogin, R.id.rowTitle).setText(R.string.settings_login_title)

        val server = findViewById<View>(R.id.rowServer)
        server.setOnClickListener { toggleServer() }
        findInRow<ImageView>(R.id.rowServer, R.id.rowIcon).setImageResource(R.drawable.ic_bolt)
        findInRow<TextView>(R.id.rowServer, R.id.rowTitle).setText(R.string.settings_server_title)

        val repair = findViewById<View>(R.id.rowRepair)
        repair.setOnClickListener { confirmRepair() }
        findInRow<ImageView>(R.id.rowRepair, R.id.rowIcon).setImageResource(R.drawable.ic_build)
        findInRow<TextView>(R.id.rowRepair, R.id.rowTitle).setText(R.string.settings_repair_title)

        val version = findViewById<View>(R.id.rowVersion)
        findInRow<ImageView>(R.id.rowVersion, R.id.rowIcon).setImageResource(R.drawable.ic_settings)
        findInRow<TextView>(R.id.rowVersion, R.id.rowTitle).setText(R.string.settings_version_title)

        val device = findViewById<View>(R.id.rowDevice)
        findInRow<ImageView>(R.id.rowDevice, R.id.rowIcon).setImageResource(R.drawable.ic_agents)
        findInRow<TextView>(R.id.rowDevice, R.id.rowTitle).setText(R.string.settings_device_title)
        findInRow<TextView>(R.id.rowDevice, R.id.rowValue).setText(R.string.settings_device_value)
    }

    // ── Agent list ─────────────────────────────────────────────────────────

    private fun setupAgentList() {
        agentList.layoutManager = LinearLayoutManager(this)
        agentAdapter = AgentAdapter(
            agents = AgentCatalog.all,
            activeAgentId = currentAgent().id,
            onClick = ::onAgentSelected,
            onRun = ::onRunAgent,
        )
        agentList.adapter = agentAdapter
    }

    private fun currentAgent(): Agent =
        AgentCatalog.byId(prefs.getString(KEY_ACTIVE_AGENT, "codex") ?: "codex")

    private fun onAgentSelected(agent: Agent) {
        if (!agent.bundled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.agents_not_bundled_title)
                .setMessage(
                    getString(R.string.agents_not_bundled_message, agent.name)
                )
                .setPositiveButton(R.string.agents_select_anyway) { _, _ ->
                    selectAgent(agent)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            selectAgent(agent)
        }
    }

    /**
     * Run button: starts the selected agent and opens its UI in the WebView
     * once the server is actually running. For non-bundled agents it shows
     * the install guide instead.
     */
    private fun onRunAgent(agent: Agent) {
        val url = agent.webUrl
        if (!agent.bundled || url == null) {
            showInstallGuide(agent)
            return
        }
        selectAgent(agent)
        when (agent.id) {
            "openclaw" -> {
                if (serverManager.isOpenClawInstalled()) {
                    ensureOpenClawRunning(onReady = { showAgentWebUi(url) })
                } else {
                    Toast.makeText(
                        this,
                        R.string.agents_openclaw_not_installed,
                        Toast.LENGTH_LONG,
                    ).show()
                    maybeAskOpenClaw()
                }
            }
            else -> startServerAndOpen(url)
        }
    }

    /**
     * Starts the codex-web-local server if it is not running, then opens the
     * agent UI in the WebView. Only meaningful after the environment setup.
     */
    private fun startServerAndOpen(url: String) {
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) {
            Toast.makeText(this, R.string.agents_wait_setup, Toast.LENGTH_LONG).show()
            return
        }
        if (serverManager.isRunning) {
            showAgentWebUi(url)
            return
        }
        Toast.makeText(this, R.string.status_starting_server, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                updateStatus("Starting server…")
                if (!serverManager.startServer()) {
                    throw RuntimeException("Failed to start server")
                }
                if (!serverManager.waitForServer(timeoutMs = 90_000)) {
                    throw RuntimeException("Server did not start in time")
                }
                onUi {
                    updateSettingsRows()
                    refreshAgentButtons()
                    showAgentWebUi(url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
                onUi {
                    Toast.makeText(this, e.message ?: "Server failed", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun selectAgent(agent: Agent) {
        prefs.edit().putString(KEY_ACTIVE_AGENT, agent.id).apply()
        updateActiveAgentCard()
        agentAdapter?.updateActiveAgent(agent.id)
        Toast.makeText(this, getString(R.string.agents_now_using, agent.name), Toast.LENGTH_SHORT).show()
        showScreen(SCREEN_HOME)
        refreshAgentButtons()
    }

    private fun updateActiveAgentCard() {
        val agent = currentAgent()
        agentAvatar.text = agent.name.first().uppercaseChar().toString()
        agentAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, agent.colorRes)
        )
        agentName.text = agent.name
        agentTagline.text = agent.tagline
        if (agent.bundled) {
            agentRuntimeHint.visibility = View.GONE
        } else {
            agentRuntimeHint.visibility = View.VISIBLE
            agentRuntimeHint.text = getString(R.string.home_runtime_hint)
        }
        refreshAgentButtons()
    }

    // ── Settings ───────────────────────────────────────────────────────────

    private fun setupVersion() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.1"
        } catch (_: Exception) {
            "0.1.1"
        }
        versionText.text = version
        buildTag.text = getString(R.string.home_build_tag, version)
    }

    /**
     * On the first launch after an update (or a fresh install) this shows a
     * dialog naming the exact build, so it is impossible to confuse this APK
     * with any other AnyClaw build.
     */
    private fun announceNewBuild() {
        val version = BuildConfig.VERSION_NAME
        val lastSeen = prefs.getString(KEY_LAST_SEEN_VERSION, null)
        if (lastSeen == version) return
        prefs.edit().putString(KEY_LAST_SEEN_VERSION, version).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.build_welcome_title)
            .setMessage(getString(R.string.build_welcome_message, version))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun updateSettingsRows() {
        serverStatusText.text = getString(
            if (serverManager.isRunning) R.string.settings_server_running else R.string.settings_server_stopped
        )
        loginStatusText.text = getString(
            if (cachedLoggedIn == true) {
                R.string.settings_login_logged_in
            } else {
                R.string.settings_login_not_logged_in
            }
        )
    }

    // ── Setup / repair ─────────────────────────────────────────────────────

    private fun startSetup() {
        if (setupRunning) return
        setupRunning = true
        setStatusUi(
            settingUp = true,
            ok = false,
            title = getString(R.string.home_status_setting_up),
            detail = getString(R.string.home_status_detail_setting_up),
        )
        Thread {
            try {
                val finalDetail = runEnvironmentSetup()
                onUi {
                    setupRunning = false
                    prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
                    cachedLoggedIn = try { serverManager.isLoggedIn() } catch (e: Exception) { false }
                    setStatusUi(
                        settingUp = false,
                        ok = true,
                        title = getString(R.string.home_status_ready),
                        detail = finalDetail ?: getString(R.string.home_status_detail_ready),
                    )
                    maybeAskOpenClaw()
                    refreshAgentButtons()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Environment setup failed", e)
                onUi {
                    setupRunning = false
                    setStatusUi(
                        settingUp = false,
                        ok = false,
                        title = getString(R.string.home_status_needs_repair),
                        detail = e.message ?: getString(R.string.error_bootstrap),
                    )
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun runEnvironmentSetup(): String? {
        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus(getString(R.string.status_extracting_bootstrap))
            BootstrapInstaller.install(this) { msg -> updateDetail(msg) }
        }
        updateStatus("Environment ready")

        // Step 1b: Install proot
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…", "Needed for package management")
            if (!serverManager.installProot { msg -> updateDetail(msg) }) {
                throw RuntimeException("Failed to install proot")
            }
        }
        updateStatus("proot ready")

        // Step 2: Install Node.js
        if (!serverManager.isNodeInstalled()) {
            updateStatus("Installing Node.js (first run)…", "This may take a few minutes")
            if (!serverManager.installNode { msg -> updateDetail(msg) }) {
                throw RuntimeException("Failed to install Node.js")
            }
        }
        updateStatus("Node.js ready")

        // Step 2b: Install Python (best effort)
        if (!serverManager.isPythonInstalled()) {
            updateStatus("Installing Python…")
            if (!serverManager.installPython { msg -> updateDetail(msg) }) {
                Log.w(TAG, "Python install failed — continuing without it")
            }
        }

        // Step 2c: Install bionic-compat.js shim
        serverManager.ensureBionicCompat()

        // Step 3: Install Codex CLI
        if (!serverManager.isCodexInstalled()) {
            updateStatus("Installing Codex CLI…", "This may take a few minutes")
            if (!serverManager.installCodex { msg -> updateDetail(msg) }) {
                throw RuntimeException("Failed to install Codex")
            }
        }
        serverManager.ensureCodexWrapperScript()

        // Step 3a: Extract web UI from APK assets (every launch)
        updateStatus("Updating web UI…")
        serverManager.installServerBundle { msg -> updateDetail(msg) }

        // Step 3b: Install native platform binary
        if (!serverManager.isPlatformBinaryInstalled()) {
            updateStatus("Installing Codex platform binary…")
            if (!serverManager.installPlatformBinary { msg -> updateDetail(msg) }) {
                throw RuntimeException("Failed to install Codex platform binary")
            }
        }
        updateStatus("Codex ready")

        // Step 3c: Full-access config + default workspace
        serverManager.ensureFullAccessConfig()
        serverManager.ensureDefaultWorkspace()

        // Step 4: CONNECT proxy (needed for native binary DNS/TLS)
        updateStatus("Starting network proxy…")
        if (!serverManager.startProxy()) {
            throw RuntimeException("Failed to start network proxy")
        }

        // Step 5: Authenticate via `codex login`. This NEVER blocks setup:
        // if the browser login or API key entry is skipped, the environment
        // is still marked ready and the user can finish login later from
        // Settings → Login.
        updateStatus("Checking authentication…")
        var authenticated = serverManager.isLoggedIn()
        if (!authenticated) {
            updateStatus("Login required — opening browser…")
            val authOk = serverManager.loginWithUrl(
                onLoginUrl = { url ->
                    runOnUiThread {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                onProgress = { msg -> updateDetail(msg) },
            )
            if (!authOk && !serverManager.isLoggedIn()) {
                updateStatus("Browser login didn't finish — enter API key manually")
                val apiKey = requestApiKey()
                if (apiKey.isBlank()) {
                    updateStatus("Login skipped — do it later from Settings → Login")
                } else {
                    authenticated = serverManager.loginWithApiKey(apiKey)
                }
            } else {
                authenticated = true
            }
        }

        var finalDetail: String? = null
        if (authenticated) {
            updateStatus("Authenticated")
            // Step 6: Health check (non-fatal — reports, never fails setup)
            updateStatus("Verifying API access…", "Sending test message")
            if (!serverManager.healthCheck { msg -> updateDetail(msg) }) {
                Log.w(TAG, "API health check failed — environment still marked ready")
                finalDetail = getString(R.string.home_status_api_pending)
                updateStatus(
                    "API check failed",
                    getString(R.string.home_status_api_pending),
                )
            } else {
                updateStatus("API verified")
            }
        } else {
            finalDetail = getString(R.string.home_status_login_pending)
            updateStatus(
                "Login pending",
                getString(R.string.home_status_login_pending),
            )
        }

        // Step 7: Start web server
        updateStatus("Starting server…")
        if (!serverManager.startServer()) {
            throw RuntimeException("Failed to start server")
        }

        // Step 8: Wait for ready
        updateStatus("Waiting for server…")
        if (!serverManager.waitForServer(timeoutMs = 90_000)) {
            throw RuntimeException("Server did not start in time")
        }
        updateStatus("Server ready")
        return finalDetail
    }

    /**
     * OpenClaw is optional: ask once after the first successful setup, then
     * install/start it in the background if the user opted in.
     */
    private fun maybeAskOpenClaw() {
        if (serverManager.isOpenClawInstalled()) {
            ensureOpenClawRunning()
            return
        }
        if (prefs.getBoolean(KEY_OPENCLAW_DECIDED, false)) {
            if (prefs.getBoolean(KEY_OPENCLAW_OPT_IN, false)) {
                installOpenClawInBackground()
            }
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.openclaw_install_title)
            .setMessage(R.string.openclaw_install_message)
            .setCancelable(false)
            .setPositiveButton(R.string.openclaw_install_yes) { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_OPENCLAW_DECIDED, true)
                    .putBoolean(KEY_OPENCLAW_OPT_IN, true)
                    .apply()
                installOpenClawInBackground()
            }
            .setNegativeButton(R.string.openclaw_install_no) { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_OPENCLAW_DECIDED, true)
                    .putBoolean(KEY_OPENCLAW_OPT_IN, false)
                    .apply()
            }
            .show()
    }

    private fun installOpenClawInBackground(onReady: (() -> Unit)? = null) {
        Thread {
            try {
                if (!serverManager.isOpenClawInstalled()) {
                    updateStatus("Installing build dependencies…")
                    serverManager.installOpenClawDeps { msg -> updateDetail(msg) }

                    updateStatus("Installing OpenClaw…", "This may take several minutes")
                    if (!serverManager.installOpenClaw { msg -> updateDetail(msg) }) {
                        Log.w(TAG, "OpenClaw install failed — continuing without it")
                        return@Thread
                    }
                }
                serverManager.configureOpenClawAuth()
                updateStatus("Starting OpenClaw gateway…")
                serverManager.startOpenClawGateway()
                updateStatus("Starting OpenClaw Control UI…")
                serverManager.startOpenClawControlUiServer()
                serverManager.waitForPort(CodexServerManager.OPENCLAW_CONTROL_UI_PORT, 60_000)
                openclawUiStarted = true
                onUi {
                    refreshAgentButtons()
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenClaw setup failed", e)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun ensureOpenClawRunning(onReady: (() -> Unit)? = null) {
        Thread {
            try {
                serverManager.configureOpenClawAuth()
                updateStatus("Starting OpenClaw gateway…")
                serverManager.startOpenClawGateway()
                updateStatus("Starting OpenClaw Control UI…")
                serverManager.startOpenClawControlUiServer()
                serverManager.waitForPort(CodexServerManager.OPENCLAW_CONTROL_UI_PORT, 60_000)
                openclawUiStarted = true
                onUi {
                    refreshAgentButtons()
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenClaw start failed", e)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun confirmRepair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.repair_confirm_title)
            .setMessage(R.string.repair_confirm_message)
            .setPositiveButton(R.string.repair_confirm_yes) { _, _ ->
                hideAgentWebUi()
                Thread {
                    try {
                        updateStatus(getString(R.string.status_repairing), "Stopping servers")
                        serverManager.stopServer()

                        // Wipe the prefix and re-extract it from the APK assets.
                        // This is what actually repairs corrupted/missing files —
                        // re-running setup alone leaves broken files in place.
                        BootstrapInstaller.reinstall(this) { msg -> updateDetail(msg) }
                        if (!BootstrapInstaller.isBootstrapHealthy(this)) {
                            Log.w(
                                TAG,
                                "Bootstrap still incomplete after reinstall — setup fallbacks will finish it"
                            )
                        }
                        onUi {
                            prefs.edit().putBoolean(KEY_SETUP_DONE, false).apply()
                            startSetup()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Environment repair failed", e)
                        onUi {
                            setStatusUi(
                                settingUp = false,
                                ok = false,
                                title = getString(R.string.home_status_needs_repair),
                                detail = e.message ?: getString(R.string.error_bootstrap),
                            )
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── SSH (remote control) ──────────────────────────────────────────────

    private fun refreshSshCard() {
        val running = sshManager.isRunning
        sshStatusText.setText(
            if (running) {
                getString(R.string.ssh_status_running, SshManager.SSH_PORT)
            } else {
                getString(R.string.ssh_status_stopped)
            }
        )
        sshStartBtn.setText(if (running) R.string.ssh_stop else R.string.ssh_start)
        if (running) {
            val ip = sshManager.localIpAddress()
            sshConnectionText.visibility = View.VISIBLE
            sshConnectionText.text = if (ip != null) {
                getString(R.string.ssh_connect_hint, SshManager.SSH_PORT, ip)
            } else {
                getString(R.string.ssh_no_ip)
            }
        } else {
            sshConnectionText.visibility = View.GONE
        }
    }

    private fun toggleSsh() {
        if (sshManager.isRunning) {
            sshStartBtn.isEnabled = false
            Thread {
                sshManager.stop()
                onUi {
                    sshStartBtn.isEnabled = true
                    refreshSshCard()
                }
            }.apply { isDaemon = true; start() }
            return
        }
        if (!sshManager.isPasswordSet()) {
            Toast.makeText(this, R.string.ssh_no_password, Toast.LENGTH_SHORT).show()
            promptSshPassword()
            return
        }
        sshStartBtn.isEnabled = false
        Thread {
            var failure: String? = null
            val ok = sshManager.start { msg ->
                Log.d(TAG, "[ssh] $msg")
                failure = msg
            }
            onUi {
                sshStartBtn.isEnabled = true
                refreshSshCard()
                if (!ok) {
                    Toast.makeText(
                        this,
                        failure ?: getString(R.string.ssh_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun promptSshPassword() {
        val input = EditText(this).apply {
            hint = getString(R.string.ssh_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_password_dialog_title)
            .setMessage(R.string.ssh_password_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val password = input.text.toString()
                Thread {
                    sshManager.setPassword(password)
                    onUi {
                        refreshSshCard()
                        Toast.makeText(this, R.string.ssh_password_saved, Toast.LENGTH_SHORT).show()
                    }
                }.apply { isDaemon = true; start() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Shows the install guide for an agent whose runtime is not bundled in
     * this build yet.
     */
    private fun showInstallGuide(agent: Agent) {
        val guideRes = when (agent.id) {
            "opencode" -> R.string.guide_opencode
            "hermes" -> R.string.guide_hermes
            "claude" -> R.string.guide_claude
            "gemini" -> R.string.guide_gemini
            "qwen" -> R.string.guide_qwen
            "aider" -> R.string.guide_aider
            "goose" -> R.string.guide_goose
            "continue" -> R.string.guide_continue
            else -> R.string.guide_coming_soon
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(agent.name)
            .setMessage(guideRes)
        val cmd = agent.installCommand
        if (cmd != null) {
            builder.setPositiveButton(R.string.agents_install_in_terminal) { _, _ ->
                startActivity(
                    Intent(this, TerminalActivity::class.java)
                        .putExtra(TerminalActivity.EXTRA_BOOT_COMMAND, cmd)
                )
            }
        }
        builder.setNegativeButton(R.string.cancel, null)
        builder.show()
    }

    // ── Login (Settings) ───────────────────────────────────────────────────

    private fun startLoginFlow() {
        if (!serverManager.isCodexInstalled()) {
            Toast.makeText(this, R.string.status_installing_codex, Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                if (!serverManager.isRunning) {
                    serverManager.startProxy()
                }
                updateStatus("Checking authentication…")
                if (serverManager.isLoggedIn()) {
                    onUi { Toast.makeText(this, R.string.settings_login_logged_in, Toast.LENGTH_SHORT).show() }
                } else {
                    updateStatus("Login required — opening browser…")
                    val authOk = serverManager.loginWithUrl(
                        onLoginUrl = { url ->
                            runOnUiThread {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                        onProgress = { msg -> updateDetail(msg) },
                    )
                    if (!authOk && !serverManager.isLoggedIn()) {
                        val apiKey = requestApiKey()
                        if (apiKey.isNotBlank()) {
                            serverManager.loginWithApiKey(apiKey)
                        }
                    }
                }
                onUi {
                    cachedLoggedIn = try { serverManager.isLoggedIn() } catch (e: Exception) { false }
                    updateSettingsRows()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login flow failed", e)
                onUi { Toast.makeText(this, e.message ?: "Login failed", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun toggleServer() {
        if (serverManager.isRunning) {
            serverManager.stopServer()
            updateSettingsRows()
            hideAgentWebUi()
            return
        }
        Thread {
            try {
                onUi {
                    Toast.makeText(this, R.string.status_starting_server, Toast.LENGTH_SHORT).show()
                }
                updateStatus("Starting server…")
                if (!serverManager.startServer()) {
                    throw RuntimeException("Failed to start server")
                }
                if (!serverManager.waitForServer(timeoutMs = 90_000)) {
                    throw RuntimeException("Server did not start in time")
                }
                onUi {
                    updateSettingsRows()
                    refreshAgentButtons()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
                onUi { Toast.makeText(this, e.message ?: "Server failed", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    // ── Agent Web UI (only shown when the server is running) ───────────────

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        agentWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
        }

        agentWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean = false
        }

        agentWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[WebView] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}")
                return true
            }
        }
    }

    private fun refreshAgentButtons() {
        val agent = currentAgent()
        // Start/Stop are always visible; labels reflect what the button does.
        agentStartBtn.text = getString(
            if (agent.bundled && agent.webUrl != null) {
                R.string.home_launch_agent
            } else {
                R.string.agents_install
            }
        )
        val running = isActiveAgentRunning(agent)
        agentStopBtn.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.chip_ready_fg else R.color.text_faint,
            )
        )
        agentStopBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (running) R.color.chip_ready_bg else R.color.nav_fill,
            )
        )
    }

    private fun isActiveAgentRunning(agent: Agent): Boolean = when (agent.id) {
        "openclaw" -> serverManager.isOpenClawRunning()
        "codex" -> serverManager.isRunning
        else -> false
    }

    /**
     * Stops the active agent's server (Codex web server, or the OpenClaw
     * gateway + Control UI) and hides its web UI. Safe to tap anytime.
     */
    private fun stopActiveAgent() {
        val agent = currentAgent()
        val stopping = isActiveAgentRunning(agent)
        Thread {
            when {
                agent.id == "openclaw" -> {
                    openclawUiStarted = false
                    serverManager.stopOpenClawServers()
                }
                stopping -> serverManager.stopServer()
                else -> Unit
            }
            onUi {
                hideAgentWebUi()
                updateSettingsRows()
                refreshAgentButtons()
                Toast.makeText(
                    this,
                    if (stopping) {
                        getString(R.string.agents_stopped, agent.name)
                    } else {
                        getString(R.string.agents_not_running)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun launchActiveAgent() {
        val agent = currentAgent()
        if (!agent.bundled || agent.webUrl == null) {
            showInstallGuide(agent)
            return
        }
        startServerAndOpen(agent.webUrl)
    }

    private fun showAgentWebUi(url: String) {
        agentWebView.loadUrl(url)
        agentWebView.visibility = View.VISIBLE
        webMenuBtn.visibility = View.VISIBLE
        bottomNav.visibility = View.GONE
    }

    private fun hideAgentWebUi() {
        agentWebView.visibility = View.GONE
        webMenuBtn.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        agentWebView.stopLoading()
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private fun onUi(block: () -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            block()
        }
    }

    private fun setStatusUi(
        settingUp: Boolean,
        ok: Boolean,
        title: String? = null,
        detail: String? = null,
    ) {
        if (title != null) statusTitle.text = title
        if (detail != null) statusDetail.text = detail
        setupProgress.visibility = if (settingUp) View.VISIBLE else View.GONE
        statusActionBtn.visibility = if (!settingUp && !ok) View.VISIBLE else View.GONE
        statusHint.visibility = if (settingUp) View.VISIBLE else View.GONE
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                when {
                    settingUp -> R.color.accent_amber
                    ok -> R.color.accent_emerald
                    else -> R.color.accent_rose
                },
            )
        )
    }

    private fun updateStatus(text: String, detail: String? = null) {
        onUi { setStatusUi(settingUp = true, ok = false, title = text, detail = detail) }
    }

    private fun updateDetail(text: String) {
        onUi {
            statusDetail.text = text
            statusDetail.visibility = View.VISIBLE
        }
    }

    /**
     * Fallback: prompt for API key if browser login fails.
     */
    private fun requestApiKey(): String {
        var result = ""
        val lock = Object()

        runOnUiThread {
            val input = EditText(this).apply {
                hint = getString(R.string.api_key_hint)
                setSingleLine(true)
            }
            val padding = (24 * resources.displayMetrics.density).toInt()
            val container = android.widget.FrameLayout(this).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.api_key_title)
                .setMessage(R.string.api_key_message)
                .setView(container)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ ->
                    result = input.text.toString().trim()
                    synchronized(lock) { lock.notifyAll() }
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    synchronized(lock) { lock.notifyAll() }
                }
                .show()
        }

        synchronized(lock) {
            lock.wait(300_000)
        }
        return result
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, CodexForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
