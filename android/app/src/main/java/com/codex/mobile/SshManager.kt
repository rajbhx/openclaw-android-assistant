package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Dropbear SSH server for remote control of the app's Termux-style
 * environment.
 *
 * The Termux dropbear binary (and libtermux-auth) have paths baked in at
 * build time that cannot be rewritten in the ELF:
 *   - login shell:      /data/data/com.termux/files/usr/bin/sh
 *   - password hash:    /data/data/com.termux/files/home/.termux_authinfo
 * The server is therefore started under proot, which bind-maps the app's
 * real files dir onto that baked prefix path. Both the login shell and the
 * password check then resolve to the app's actual environment.
 *
 * Authentication uses libtermux-auth: the password hash file is the raw
 * 20-byte PBKDF2-HMAC-SHA1 digest (salt "Termux!", 65536 iterations) that
 * Termux's `passwd` writes to ~/.termux_authinfo.
 */
class SshManager(private val context: Context) {

    companion object {
        private const val TAG = "SshManager"
        private const val PREFS = "anyclaw_prefs"
        private const val KEY_PASSWORD_SET = "ssh_password_set"
        private const val TERMUX_HOME = "/data/data/com.termux/files"
        private const val TERMUX_AUTH_SALT = "Termux!"
        private const val PBKDF2_ITERATIONS = 65536
        private const val HOST_KEY = "dropbear_ed25519_host_key"

        /** Port the SSH server listens on (avoids the app's own server ports). */
        const val SSH_PORT = 8022
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val serverManager by lazy { CodexServerManager(context) }

    private var sshProcess: Process? = null

    val isRunning: Boolean
        get() = sshProcess?.isRunning() == true

    /**
     * API 24-compatible replacement for [java.lang.Process.isAlive] (added
     * in API 26): a process is running unless exitValue() reports it exited.
     */
    private fun Process.isRunning(): Boolean =
        try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

    fun isPasswordSet(): Boolean = prefs.getBoolean(KEY_PASSWORD_SET, false)

    /**
     * Best-effort LAN IPv4 for the connection hint shown in the UI.
     */
    fun localIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) return host
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve local IP: ${e.message}")
        }
        return null
    }

    /**
     * Store the SSH password in Termux's authinfo format. Blank clears it
     * (password login disabled).
     */
    fun setPassword(password: String) {
        val paths = BootstrapInstaller.getPaths(context)
        val authFile = File(paths.homeDir, ".termux_authinfo")
        if (password.isBlank()) {
            authFile.delete()
            prefs.edit().putBoolean(KEY_PASSWORD_SET, false).apply()
            return
        }
        val salt = TERMUX_AUTH_SALT.toByteArray(Charsets.US_ASCII)
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 160)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val hash = factory.generateSecret(spec).encoded
        authFile.writeBytes(hash)
        authFile.setReadable(true, true)
        prefs.edit().putBoolean(KEY_PASSWORD_SET, true).apply()
    }

    /**
     * Start the dropbear server under proot. Returns true once the port is
     * actually accepting connections.
     */
    fun start(onProgress: (String) -> Unit): Boolean {
        if (isRunning) return true
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir

        if (!ensureDropbearInstalled(onProgress)) return false
        if (!ensureHostKey()) {
            onProgress("Failed to create SSH host key")
            return false
        }
        ensureAuthorizedKeysDir()
        if (!isPasswordSet()) {
            onProgress("No SSH password set yet")
            return false
        }

        val proot = File(prefix, "bin/proot")
        if (!proot.exists()) {
            onProgress("proot is missing — run Repair")
            return false
        }

        val command = listOf(
            proot.absolutePath,
            "-b", "${paths.filesDir}:$TERMUX_HOME",
            "-w", paths.homeDir,
            "--kill-on-exit",
            "$prefix/bin/dropbear",
            "-F", "-E",
            "-p", SSH_PORT.toString(),
            "-P", "$prefix/var/run/dropbear.pid",
            "-r", "$prefix/etc/dropbear/$HOST_KEY",
            "-D", "${paths.homeDir}/.ssh",
        )

        val pb = ProcessBuilder(command)
        pb.environment().clear()
        pb.environment().putAll(serverManager.shellEnvironment())
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSH server", e)
            onProgress("Failed to start SSH: ${e.message}")
            return false
        }
        sshProcess = proc

        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    Log.i(TAG, "[ssh] $line")
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.d(TAG, "SSH output stream closed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }

        // Wait for the port to accept connections.
        var listening = false
        for (attempt in 0 until 10) {
            if (!proc.isRunning()) break
            try {
                Socket("127.0.0.1", SSH_PORT).use { }
                listening = true
                break
            } catch (_: Exception) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (!listening || !proc.isRunning()) {
            sshProcess = null
            // Clean up a half-started server (e.g. proot ran but dropbear
            // failed to bind) so a retry doesn't hit a stale pidfile.
            try {
                val pid = File(prefix, "var/run/dropbear.pid")
                    .readText().trim().toIntOrNull()
                if (pid != null && pid > 0) {
                    android.os.Process.killProcess(pid)
                }
            } catch (_: Exception) {
            }
            try {
                proc.destroy()
            } catch (_: Exception) {
            }
            onProgress("SSH server exited before listening — check logcat")
            return false
        }
        onProgress("SSH server listening on port $SSH_PORT")
        return true
    }

    /**
     * Stop the SSH server: kill dropbear (via its pidfile), then proot.
     */
    fun stop() {
        val proc = sshProcess ?: return
        sshProcess = null
        val paths = BootstrapInstaller.getPaths(context)
        val pidFile = File(paths.prefixDir, "var/run/dropbear.pid")
        try {
            val pid = pidFile.readText().trim().toIntOrNull()
            if (pid != null && pid > 0) {
                android.os.Process.killProcess(pid)
            }
        } catch (_: Exception) {
            // Stale or unreadable pidfile — proot cleanup below still runs.
        }
        try {
            proc.destroy()
        } catch (_: Exception) {
        }
        try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Log.i(TAG, "SSH server stopped")
    }

    /**
     * Dropbear comes preinstalled in the bundled prefix; on manually-built
     * debug APKs it is installed on demand from the Termux repository.
     */
    private fun ensureDropbearInstalled(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        if (File(prefix, "bin/dropbear").exists() &&
            File(prefix, "lib/libtermux-auth.so").exists()
        ) {
            return true
        }

        onProgress("Installing SSH server (first run)…")
        val termuxPrefix = "/data/data/com.termux/files/usr"

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated dropbear zlib termux-auth openssl 2>&1
        """.trimIndent()
        serverManager.runInPrefix(downloadCmd, onOutput = { onProgress(it) })

        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _dropbear_stage &&
            for deb in dropbear*.deb zlib*.deb termux-auth*.deb openssl*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _dropbear_stage/ 2>&1
            done &&
            if [ -d "_dropbear_stage$termuxPrefix" ]; then
                cp -a _dropbear_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_dropbear_stage/usr" ]; then
                cp -a _dropbear_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/dropbear" "$prefix/bin/dropbearkey" "$prefix/bin/dropbearmulti" 2>/dev/null
            chmod 700 "$prefix/lib/libtermux-auth.so" 2>/dev/null
            rm -rf _dropbear_stage dropbear*.deb zlib*.deb termux-auth*.deb openssl*.deb 2>/dev/null
            echo "dropbear installed"
        """.trimIndent()
        serverManager.runInPrefix(extractCmd, onOutput = { onProgress(it) })

        return File(prefix, "bin/dropbear").exists() &&
            File(prefix, "lib/libtermux-auth.so").exists()
    }

    private fun ensureHostKey(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val keyDir = File(prefix, "etc/dropbear")
        keyDir.mkdirs()
        File(prefix, "var/run").mkdirs()
        val hostKey = File(keyDir, HOST_KEY)
        if (hostKey.exists()) return true
        val code = serverManager.runInPrefix(
            "dropbearkey -t ed25519 -f $hostKey 2>&1 && chmod 600 $hostKey"
        )
        return code == 0 && hostKey.exists()
    }

    /**
     * dropbear rejects group/world-writable authorized_keys files, so the
     * .ssh dir is created with owner-only permissions. The file is left
     * empty — password auth is the primary path; users can add their own
     * public keys here for key-based logins.
     */
    private fun ensureAuthorizedKeysDir() {
        val paths = BootstrapInstaller.getPaths(context)
        val sshDir = File(paths.homeDir, ".ssh")
        sshDir.mkdirs()
        val authKeys = File(sshDir, "authorized_keys")
        if (!authKeys.exists()) {
            authKeys.createNewFile()
        }
        serverManager.runInPrefix(
            "chmod 700 ${sshDir.absolutePath}; chmod 600 ${authKeys.absolutePath}"
        )
    }
}
