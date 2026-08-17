package com.codex.mobile.repository

import android.content.Context
import android.util.Log
import com.codex.mobile.BootstrapInstaller
import java.io.File

/**
 * Manages the Termux bootstrap lifecycle: extraction, verification,
 * and cleanup. Abstracts filesystem operations for testability.
 */
class BootstrapRepository(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapRepository"
    }

    fun isInstalled(): Boolean = BootstrapInstaller.isBootstrapInstalled(context)

    fun install(onProgress: (String) -> Unit) {
        Log.i(TAG, "Installing bootstrap…")
        BootstrapInstaller.install(context, onProgress)
        Log.i(TAG, "Bootstrap installed successfully")
    }

    fun getPrefixDir(): String {
        return BootstrapInstaller.getPaths(context).prefixDir
    }

    fun getHomeDir(): String {
        return BootstrapInstaller.getPaths(context).homeDir
    }
}
