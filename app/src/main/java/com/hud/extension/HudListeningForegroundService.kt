package com.hud.extension

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Harmony OS often keeps [HUDNotificationListenerService] out of "Live notification listeners"
 * unless the app process is foreground. This service holds a lightweight FGS while HUD is on.
 */
class HudListeningForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!running) {
            running = true
            HudLog.i("NLS keep-alive FGS started")
            NotificationAccessHelper.onKeepAliveStarted(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        HudLog.i("NLS keep-alive FGS stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fgs_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.hud_logo)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hud_listening"
        private const val NOTIFICATION_ID = 7001

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun restart(context: Context) {
            stop(context)
            val app = context.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(app)
            }, 350)
        }

        fun start(context: Context) {
            if (running) return
            val app = context.applicationContext
            val intent = Intent(app, HudListeningForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }.onFailure { error ->
                HudLog.e("NLS keep-alive FGS start failed", error)
            }
        }

        fun stop(context: Context) {
            running = false
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, HudListeningForegroundService::class.java)
                )
            }.onFailure { error ->
                HudLog.w("NLS keep-alive FGS stop failed: ${error.message}")
            }
        }
    }
}
