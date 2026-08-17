package com.codex.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * Application class for AnyClaw.
 * Initializes notification channels and global configuration.
 */
class AnyClawApp : Application() {

    companion object {
        private const val TAG = "AnyClawApp"
        const val NOTIFICATION_CHANNEL_ID = "codex_running"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Log.i(TAG, "AnyClaw application started")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AnyClaw Server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the Codex server running in the background"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
