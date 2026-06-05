package com.hud.extension

import android.os.Handler
import android.os.Looper

/** Прямая доставка данных навигации из NotificationListenerService в Activity (без broadcast). */
object NavEventHub {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var serviceConnected: Boolean = false
        private set

    @Volatile
    var lastGuidance: NavGuidance? = null
        private set

    @Volatile
    private var lastGuidancePublishedAt: Long = 0L

    private const val STALE_GUIDANCE_MS = 15_000L

    private var navConsumer: ((NavUpdate) -> Unit)? = null
    private var connectionConsumer: ((Boolean) -> Unit)? = null

    data class NavUpdate(
        val guidance: NavGuidance? = null,
        val clear: Boolean = false
    )

    fun setNavConsumer(consumer: ((NavUpdate) -> Unit)?) {
        navConsumer = consumer
    }

    fun setConnectionConsumer(consumer: ((Boolean) -> Unit)?) {
        connectionConsumer = consumer
        consumer?.invoke(serviceConnected)
    }

    fun publishNav(guidance: NavGuidance, context: android.content.Context) {
        if (guidance.isPlaceholder || !guidance.hasDisplayableContent()) return
        if (!NotificationAccessHelper.isUserListeningEnabled(context)) return
        mainHandler.post {
            if (!NotificationAccessHelper.isUserListeningEnabled(context)) return@post
            if (lastGuidance?.contentEquals(guidance) == true) return@post
            serviceConnected = true
            lastGuidance = guidance
            lastGuidancePublishedAt = System.currentTimeMillis()
            HudLog.i("hub -> UI: '${guidance.instruction}' | '${guidance.routeSummaryText}'")
            NavOverlayHolder.applyGuidance(guidance, context)
            navConsumer?.invoke(NavUpdate(guidance = guidance))
            connectionConsumer?.invoke(true)
        }
    }

    fun getStaleGuidanceIfRecent(): NavGuidance? {
        val last = lastGuidance ?: return null
        return if (System.currentTimeMillis() - lastGuidancePublishedAt <= STALE_GUIDANCE_MS) last else null
    }

    @Volatile
    var yandexFullscreenPlaceholder: Boolean = false
        private set

    fun setYandexFullscreenPlaceholder(value: Boolean) {
        yandexFullscreenPlaceholder = value
    }

    fun hasLiveNavFeed(maxAgeMs: Long = 30_000L): Boolean {
        val guidance = lastGuidance ?: return false
        if (!guidance.hasDisplayableContent()) return false
        return System.currentTimeMillis() - lastGuidancePublishedAt <= maxAgeMs
    }

    fun resetLastGuidance() {
        lastGuidance = null
        lastGuidancePublishedAt = 0L
    }

    fun publishClear() {
        mainHandler.post {
            navConsumer?.invoke(NavUpdate(clear = true))
        }
    }

    /** Обновить overlay даже если текст совпадает (иконка / RemoteViews). */
    fun republishLastToOverlay(context: android.content.Context) {
        if (!NotificationAccessHelper.isUserListeningEnabled(context)) return
        val guidance = lastGuidance ?: return
        mainHandler.post {
            if (NotificationAccessHelper.isUserListeningEnabled(context)) {
                NavOverlayHolder.applyGuidance(guidance, context)
            }
        }
    }

    fun publishConnection(connected: Boolean) {
        mainHandler.post {
            serviceConnected = connected
            connectionConsumer?.invoke(connected)
        }
    }
}
