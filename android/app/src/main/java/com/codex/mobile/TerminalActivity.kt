package com.codex.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * Native PTY terminal that runs a login shell inside the bundled Termux-style
 * prefix. Used for manually fixing the environment (npm installs, config
 * edits, process control, logs) without leaving the app.
 */
class TerminalActivity : AppCompatActivity(), TerminalViewClient, TerminalSessionClient {

    companion object {
        private const val TAG = "TerminalActivity"
    }

    private lateinit var terminalView: TerminalView
    private lateinit var pathLabel: TextView
    private var session: TerminalSession? = null

    private val serverManager by lazy { CodexServerManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.terminalView)
        pathLabel = findViewById(R.id.terminalPath)

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(12)

        findViewById<View>(R.id.terminalEscBtn).setOnClickListener {
            session?.write(byteArrayOf(0x1b), 0, 1)
        }
        findViewById<View>(R.id.terminalCtrlCBtn).setOnClickListener {
            session?.writeCodePoint(false, 3)
        }
        findViewById<View>(R.id.terminalCtrlDBtn).setOnClickListener {
            session?.writeCodePoint(false, 4)
        }
        findViewById<View>(R.id.terminalKillBtn).setOnClickListener {
            session?.finishIfRunning()
        }
        findViewById<View>(R.id.terminalCloseBtn).setOnClickListener { finish() }

        startShell()
    }

    private fun startShell() {
        val paths = BootstrapInstaller.getPaths(this)
        val shellPath = "${paths.prefixDir}/bin/sh"
        pathLabel.text = shellPath

        if (!serverManager.isShellInstalled() || !File(shellPath).exists()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_env_not_ready_title)
                .setMessage(R.string.terminal_env_not_ready_message)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        val envArray = serverManager.shellEnvironment()
            .map { "${it.key}=${it.value}" }
            .toTypedArray()

        val newSession = TerminalSession(
            shellPath,
            paths.homeDir,
            arrayOf("-sh"),
            envArray,
            1000,
            this,
        )
        newSession.mSessionName = getString(R.string.terminal_title)
        session = newSession

        // Attach once the view has a real size so the PTY can be created
        // with the correct column/row count.
        terminalView.post { terminalView.attachSession(newSession) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) terminalView.updateSize()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }

    // ── TerminalViewClient ───────────────────────────────────────────────

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) = Unit

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) { Log.e(tag, message) }

    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }

    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }

    // ── TerminalSessionClient ────────────────────────────────────────────

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        runOnUiThread {
            Toast.makeText(this, R.string.terminal_exited, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.terminal_title), text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this) ?: return
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null
}
