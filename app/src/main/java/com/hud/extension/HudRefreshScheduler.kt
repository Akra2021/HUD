package com.hud.extension

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Периодическое обновление HUD (маршрут в полном экране часто не шлёт notification). */
object HudRefreshScheduler {

    private const val POLL_INTERVAL_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext
            if (!running || ctx == null || !HudOutputController.isActive(ctx)) {
                stop()
                return
            }
            HUDNotificationListenerService.refreshActiveNotifications("poll")
            if (AccessibilityHelper.isFeatureAvailable(ctx)) {
                NavAccessibilityService.requestScan(ctx, "poll")
            }
            if (NavEventHub.lastGuidance != null) {
                NavEventHub.republishLastToOverlay(ctx)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        HudLog.i("HUD refresh poll started (${POLL_INTERVAL_MS}ms)")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(pollRunnable)
        HudLog.i("HUD refresh poll stopped")
    }
}
