package com.hud.extension

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class HUDNotificationListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationAccessHelper.reportServiceConnected()
        HudLog.i("NLS onCreate")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        HudLog.i("NLS onDestroy")
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationAccessHelper.reportServiceConnected()
        HudLog.i("NLS connected hud=${HudPreferences.isHudEnabled(this)} selected=${HudPreferences.getSelectedNavPackage(this)}")
        NavEventHub.publishConnection(true)
        if (HudPreferences.usesNotificationNavPath(this)) {
            publishBestGuidance("onListenerConnected")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        HudLog.i("NLS disconnected")
        NavEventHub.publishConnection(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (HudPreferences.isDgisPackage(sbn.packageName)) {
            HudRemoteViewDecoder.logNotification(sbn)
        }

        if (!HudPreferences.isHudEnabled(this)) return
        if (!NavNotificationParser.isNavPackage(sbn.packageName)) return
        if (!HudPreferences.isSelectedNavPackage(this, sbn.packageName)) return
        HudLog.i("posted pkg=${sbn.packageName} id=${sbn.id}")
        publishFromNotification(sbn, "posted")
        publishBestGuidance("posted")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!HudPreferences.isHudEnabled(this)) return
        if (!NavNotificationParser.isNavPackage(sbn.packageName)) return
        if (!HudPreferences.isSelectedNavPackage(this, sbn.packageName)) return
        HudLog.i("removed pkg=${sbn.packageName} id=${sbn.id}")
        publishBestGuidance("removed")
    }

    fun publishBestGuidance(reason: String): NavGuidance? {
        if (!HudPreferences.usesNotificationNavPath(this)) return null
        var guidance = findBestGuidance(reason)
        if (guidance == null && AccessibilityHelper.isFeatureAvailable(this)) {
            NavAccessibilityService.requestScan(this, "notifEmpty:$reason")
        }
        if (guidance == null && NavEventHub.yandexFullscreenPlaceholder) {
            HudLog.i("publish ($reason): Yandex fullscreen — show status only")
            NavEventHub.resetLastGuidance()
            NavOverlayHolder.showWaiting()
            NavEventHub.publishClear()
            return null
        }
        if (guidance == null) {
            if (reason.contains("removed")) {
                NavEventHub.resetLastGuidance()
                NavEventHub.resetNavPlaceholders()
            } else {
                guidance = NavEventHub.getStaleGuidanceIfRecent(this)
                if (guidance != null) {
                    HudLog.i("publish ($reason): using stale '${guidance.instruction}'")
                }
            }
            if (guidance == null) {
                HudLog.i("publish ($reason): no guidance from notifications")
                NavEventHub.resetLastGuidance()
                NavOverlayHolder.showWaiting()
                NavEventHub.publishClear()
                return null
            }
        }
        HudLog.i(
            "publish ($reason): line1='${guidance.instruction}' line2='${guidance.detail}' " +
                "line3='${guidance.routeSummaryText}' score=${guidance.detailScore}"
        )
        val source = HudPreferences.getSelectedNavPackage(this)
        NavEventHub.publishNav(guidance, this, source)
        return guidance
    }

    private fun publishFromNotification(sbn: StatusBarNotification, reason: String) {
        if (!HudPreferences.isHudEnabled(this)) return
        val guidance = NavNotificationParser.parse(sbn, this) { icon ->
            NavNotificationParser.iconToBitmap(icon, this)
        } ?: return
        if (guidance.isPlaceholder || !guidance.hasDisplayableContent()) return
        HudLog.i("publish direct ($reason) id=${sbn.id}: '${guidance.instruction}'")
        NavEventHub.publishNav(guidance, this, sbn.packageName)
    }

    private fun findBestGuidance(reason: String = ""): NavGuidance? {
        return try {
            val active = activeNotifications
            if (active == null) {
                HudLog.i("findBestGuidance ($reason): activeNotifications=null (not bound yet)")
                return null
            }
            HudLog.i("findBestGuidance ($reason): active count=${active.size}")
            active.forEach { sbn ->
                HudLog.i("  active: id=${sbn.id} pkg=${sbn.packageName} tag=${sbn.tag}")
            }
            NavNotificationParser.pickBest(active, this) { icon ->
                NavNotificationParser.iconToBitmap(icon, this)
            }
        } catch (e: Exception) {
            HudLog.e("findBestGuidance failed", e)
            null
        }
    }

    companion object {
        const val ACTION_LISTENER_STATE = "com.hud.extension.LISTENER_STATE"
        const val EXTRA_CONNECTED = "connected"

        @Volatile
        private var instance: HUDNotificationListenerService? = null

        fun refreshActiveNotifications(reason: String = "refresh") {
            val ctx = NotificationAccessHelper.getAppContext()
            if (ctx != null && !HudPreferences.usesNotificationNavPath(ctx)) return
            val service = instance
            if (service != null) {
                service.publishBestGuidance(reason)
                return
            }
            HudLog.i("refreshActiveNotifications ($reason): service instance null, need rebind")
            NotificationAccessHelper.reportServiceInstanceMissing(reason)
        }

        fun isRunning(): Boolean = instance != null
    }
}
