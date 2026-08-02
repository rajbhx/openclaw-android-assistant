package com.codex.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live logcat viewer for the app's own components (environment bootstrap,
 * servers, SSH, terminal) plus crash lines. Uses `logcat -d` (dump) and
 * optionally auto-refreshes so problems can be diagnosed from inside the
 * app without ADB or SSH.
 */
class LogcatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LogcatActivity"
        private val WATCH_TAGS = setOf(
            "CodexServerManager",
            "SshManager",
            "BootstrapInstaller",
            "MainActivity",
            "TerminalActivity",
            "LogcatActivity",
            "AndroidRuntime",
            "FATAL",
        )
        private const val MAX_LINES = 300
        private const val MAX_CHARS = 60000
        private const val REFRESH_MS = 3000L
    }

    private lateinit var logOutput: TextView
    private lateinit var autoBtn: TextView
    private val autoRefresh = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val refreshTick = object : Runnable {
        override fun run() {
            if (autoRefresh.get()) dumpLogs()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logcat)

        logOutput = findViewById(R.id.logOutput)
        autoBtn = findViewById(R.id.logAutoBtn)

        findViewById<View>(R.id.logClearBtn).setOnClickListener { clearLogs() }
        findViewById<View>(R.id.logRefreshBtn).setOnClickListener { dumpLogs() }
        findViewById<View>(R.id.logCloseBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.logCopyBtn).setOnClickListener { copyLogs() }
        findViewById<View>(R.id.logExportBtn).setOnClickListener { exportLogs() }
        autoBtn.setOnClickListener {
            autoRefresh.set(!autoRefresh.get())
            updateAutoLabel()
            // Starting the live view clears the logcat buffer once so the
            // stream only shows fresh lines from here on.
            if (autoRefresh.get()) clearLogs()
        }

        updateAutoLabel()
        dumpLogs()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshTick)
    }

    /** Clear the logcat ring buffer, then show what is left. */
    private fun clearLogs() {
        Thread {
            runCatching {
                ProcessBuilder("logcat", "-c").start().waitFor()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dumpLogs()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun copyLogs() {
        val text = logOutput.text.toString()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("logcat", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun exportLogs() {
        val text = logOutput.text.toString()
        if (text.isBlank()) return
        Thread {
            val dir = getExternalFilesDir(null) ?: filesDir
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "anyclaw-logcat-$stamp.txt")
            runCatching { file.writeText(text) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    getString(R.string.log_exported, file.absolutePath),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun updateAutoLabel() {
        autoBtn.setText(if (autoRefresh.get()) R.string.log_auto_on else R.string.log_auto_off)
    }

    private fun dumpLogs() {
        Thread {
            val lines = runCatching { readLogcat() }
                .getOrDefault(listOf(getString(R.string.log_empty)))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                logOutput.text = lines.joinToString("\n")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readLogcat(): List<String> {
        val proc = ProcessBuilder("logcat", "-d", "-v", "time").start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val kept = ArrayDeque<String>()
        var line = reader.readLine()
        while (line != null) {
            if (WATCH_TAGS.any { line.contains(it) }) {
                kept.addLast(line)
                if (kept.size > MAX_LINES) kept.removeFirst()
            }
            line = reader.readLine()
        }
        proc.waitFor()

        // Trim by total size so huge crash stacks don't freeze the UI.
        var total = 0
        val result = ArrayDeque<String>()
        for (entry in kept) {
            total += entry.length + 1
            if (total > MAX_CHARS) break
            result.addLast(entry)
        }
        return result.toList().ifEmpty { listOf(getString(R.string.log_empty)) }
    }
}
