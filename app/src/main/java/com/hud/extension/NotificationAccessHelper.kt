package com.hud.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService

object NotificationAccessHelper {

    private const val PREFS = "hud_notification_prefs"
    private const val KEY_USER_LISTENING = "user_listening_enabled"
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private const val REBIND_DELAY_MS = 250L
    private const val FORCE_RECONNECT_REBIND_MS = 900L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRebind: Runnable? = null

    /** In-memory flag — updated before SharedPreferences so services stop immediately. */
    @Volatile
    private var userListeningCached: Boolean? = null

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, HUDNotificationListenerService::class.java)

    fun isEnabled(context: Context): Boolean {
        val component = listenerComponent(context)
        val flat = component.flattenToString()
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS
        ) ?: return false

        if (enabledListeners.contains(flat)) return true

        return enabledListeners.split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == component }
    }

    fun isUserListeningEnabled(context: Context): Boolean {
        userListeningCached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getBoolean(KEY_USER_LISTENING, isEnabled(context))
        userListeningCached = value
        return value
    }

    fun setUserListeningEnabled(context: Context, enabled: Boolean) {
        userListeningCached = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_LISTENING, enabled)
            .commit()
    }

    fun cancelPendingRebind() {
        pendingRebind?.let { mainHandler.removeCallbacks(it) }
        pendingRebind = null
    }

    fun enable(context: Context): EnableResult {
        setUserListeningEnabled(context, true)
        if (!isEnabled(context)) {
            openAccessSettings(context)
            return EnableResult.OPENED_SETTINGS
        }
        forceReconnect(context)
        scheduleRebindAfterEnable(context)
        return EnableResult.REBOUND
    }

    private fun scheduleRebindAfterEnable(context: Context) {
        val appContext = context.applicationContext
        mainHandler.postDelayed({
            if (!isUserListeningEnabled(appContext) || !isEnabled(appContext)) return@postDelayed
            rebindListener(appContext)
            HUDNotificationListenerService.refreshActiveNotifications("enableFollowUp")
            HudLog.i("enable follow-up rebind")
        }, FORCE_RECONNECT_REBIND_MS + 400L)
    }

    fun disable(context: Context) {
        setUserListeningEnabled(context, false)
        cancelPendingRebind()
        HudRefreshScheduler.stop()
        NavEventHub.resetLastGuidance()
        NavEventHub.publishConnection(false)
        NavOverlayHolder.dismiss()
        val component = listenerComponent(context)
        if (NotificationListenerCompat.supportsUnbind) {
            NotificationListenerCompat.requestUnbind(component)
        } else {
            HudLog.i("disable: Harmony — unbind unavailable, processing stopped")
        }
    }

    fun refreshBindingIfEnabled(context: Context) {
        if (isUserListeningEnabled(context) && isEnabled(context)) {
            forceReconnect(context)
        }
    }

    fun forceReconnect(context: Context) {
        if (!isEnabled(context)) return

        val component = listenerComponent(context)
        pendingRebind?.let { mainHandler.removeCallbacks(it) }

        if (NotificationListenerCompat.supportsUnbind) {
            NotificationListenerCompat.requestUnbind(component)
            pendingRebind = Runnable {
                rebindListener(context)
                pendingRebind = null
            }
            mainHandler.postDelayed(pendingRebind!!, FORCE_RECONNECT_REBIND_MS)
        } else {
            HudLog.i("forceReconnect: rebind-only (Harmony OS)")
            rebindListener(context)
            mainHandler.postDelayed({
                HUDNotificationListenerService.refreshActiveNotifications("forceReconnectDelayed")
            }, FORCE_RECONNECT_REBIND_MS)
        }
    }

    fun scheduleRebind(context: Context) {
        val appContext = context.applicationContext
        pendingRebind?.let { mainHandler.removeCallbacks(it) }
        pendingRebind = Runnable {
            rebindListener(appContext)
            pendingRebind = null
        }
        mainHandler.postDelayed(pendingRebind!!, REBIND_DELAY_MS)
    }

    fun rebindListener(context: Context) {
        if (!isEnabled(context)) return
        val component = listenerComponent(context)
        if (!NotificationListenerCompat.requestRebind(component)) {
            HudLog.w("rebindListener: requestRebind unavailable")
        }
    }

    fun openAccessSettings(context: Context) {
        val intents = buildList {
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            add(
                Intent("com.huawei.systemmanager.action.ACCESS_NOTIFICATION_LISTENER_DETAIL").apply {
                    putExtra("package", context.packageName)
                    putExtra("packageName", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        for (intent in intents) {
            runCatching {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    HudLog.i("opened settings: ${intent.action}")
                    return
                }
            }.onFailure { e ->
                HudLog.w("settings open failed ${intent.action}: ${e.message}")
            }
        }
    }

    enum class EnableResult {
        OPENED_SETTINGS,
        REBOUND
    }
}
