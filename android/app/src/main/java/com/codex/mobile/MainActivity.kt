package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.codex.mobile.state.SetupState
import com.codex.mobile.ui.screens.LoadingScreen
import com.codex.mobile.ui.screens.WebViewScreen
import com.codex.mobile.ui.theme.AnyClawTheme
import com.codex.mobile.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        requestBatteryOptimizationExemption()
        startForegroundService()

        setContent {
            AnyClawTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.state.collectAsState()

                    AnimatedContent(
                        targetState = state.isComplete,
                        transitionSpec = {
                            fadeIn(tween(500)) togetherWith fadeOut(tween(300))
                        },
                        label = "screen",
                    ) { isComplete ->
                        when {
                            isComplete -> WebViewScreen()
                            else -> LoadingScreen(
                                state = state,
                                onRetry = viewModel::onRetry,
                                onApiKeySubmit = viewModel::onApiKeySubmit,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, CodexForegroundService::class.java))
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
