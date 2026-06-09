package com.hud.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object NotificationAccessHelper {

    private const val PREFS = "hud_notification_prefs"
    private const val KEY_USER_LISTENING = "user_listening_enabled"
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private const val REBIND_DELAY_MS = 250L
    private const val FORCE_RECONNECT_REBIND_MS = 900L
    private val HARMONY_REBIND_DELAYS_MS = longArrayOf(0L, 1_500L, 4_000L)
    private const val BURST_MIN_INTERVAL_MS = 20_000L
    private const val COMPONENT_RECYCLE_DELAY_MS = 900L
    private const val POST_RECYCLE_REBIND_MS = 1_200L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRebind: Runnable? = null
    private var appContext: Context? = null
    private var lastBurstAt = 0L
    private var burstScheduled = false
    private var componentRecycleInFlight = false
    private var keepAliveRecoveryDone = false

    @Volatile
    private var missingServiceCount = 0

    /** In-memory flag — updated before SharedPreferences so services stop immediately. */
    @Volatile
    private var userListeningCached: Boolean? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getAppContext(): Context? = appContext

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, HUDNotificationListenerService::class.java)

    fun reportServiceInstanceMissing(reason: String) {
        val context = appContext ?: return
        if (!HudPreferences.usesNotificationNavPath(context)) return
        val count = ++missingServiceCount
        if (count == 1 || count % 5 == 0) {
            logBindingDiagnostics(context, reason, count)
        }
        when {
            count == 1 || count == 5 ->
                beginHarmonyListening(context, recycleIfMissing = true, forceKeepAlive = true)
            count % 6 == 0 -> {
                ensureKeepAlive(context, forceRestart = true)
                scheduleHarmonyRebindBurst(context)
            }
            else -> ensureKeepAlive(context)
        }
    }

    fun ensureBindingOnResume(context: Context) {
        if (!isUserListeningEnabled(context) || !isEnabled(context)) return
        if (!HudPreferences.usesNotificationNavPath(context)) return
        if (HUDNotificationListenerService.isRunning()) return
        HudLog.i("NLS resume: binding missing, starting Harmony recovery")
        beginHarmonyListening(context, recycleIfMissing = true, forceKeepAlive = true)
    }

    fun onKeepAliveStarted(context: Context) {
        if (keepAliveRecoveryDone) return
        keepAliveRecoveryDone = true
        val appContext = context.applicationContext
        mainHandler.postDelayed({
            if (!isUserListeningEnabled(appContext) || !isEnabled(appContext)) return@postDelayed
            if (!HUDNotificationListenerService.isRunning()) {
                recycleListenerComponent(appContext)
            }
            rebindListener(appContext)
            HudLog.i("NLS keep-alive follow-up rebind")
        }, 500)
    }

    fun runHarmonyRecovery(context: Context) {
        HudLog.i("NLS Harmony recovery started")
        missingServiceCount = 0
        keepAliveRecoveryDone = false
        beginHarmonyListening(context.applicationContext, recycleIfMissing = true, forceKeepAlive = true)
    }

    private fun beginHarmonyListening(
        context: Context,
        recycleIfMissing: Boolean,
        forceKeepAlive: Boolean = false
    ) {
        if (!isUserListeningEnabled(context) || !isEnabled(context)) return
        keepAliveRecoveryDone = false
        ensureKeepAlive(context, forceRestart = forceKeepAlive)

        val needsRecycle = recycleIfMissing &&
            !NotificationListenerCompat.supportsUnbind &&
            !HUDNotificationListenerService.isRunning()

        if (needsRecycle) {
            recycleListenerComponent(context)
            return
        }
        forceReconnect(context)
    }

    private fun ensureKeepAlive(context: Context, forceRestart: Boolean = false) {
        if (!isUserListeningEnabled(context)) return
        if (forceRestart || !HudListeningForegroundService.isRunning()) {
            HudListeningForegroundService.restart(context)
        } else {
            HudListeningForegroundService.start(context)
        }
    }

    fun reportServiceConnected() {
        if (missingServiceCount > 0) {
            HudLog.i("NLS connected after missing streak=$missingServiceCount")
        }
        missingServiceCount = 0
    }

    private fun logBindingDiagnostics(context: Context, reason: String, count: Int) {
        val component = listenerComponent(context)
        val flat = component.flattenToString()
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS
        ) ?: ""
        val inList = enabledListeners.contains(flat)
        HudLog.w(
            "NLS missing ($reason) streak=$count user=${isUserListeningEnabled(context)} " +
                "access=${isEnabled(context)} inList=$inList " +
                "rebind=${NotificationListenerCompat.supportsRebind} " +
                "unbind=${NotificationListenerCompat.supportsUnbind} " +
                "running=${HUDNotificationListenerService.isRunning()}"
        )
        if (!inList) {
            HudLog.w("NLS: enable notification access for $flat in system settings")
        } else if (!NotificationListenerCompat.supportsUnbind) {
            HudLog.w(
                "NLS Harmony: allowed but not live — toggle notification access OFF/ON, keep app open"
            )
        }
    }

    private fun scheduleHarmonyRebindBurst(context: Context) {
        val now = System.currentTimeMillis()
        if (burstScheduled || now - lastBurstAt < BURST_MIN_INTERVAL_MS) return
        burstScheduled = true
        lastBurstAt = now
        val appContext = context.applicationContext
        HARMONY_REBIND_DELAYS_MS.forEach { delayMs ->
            mainHandler.postDelayed({
                if (!isUserListeningEnabled(appContext) || !isEnabled(appContext)) return@postDelayed
                rebindListener(appContext)
                HudLog.i("NLS Harmony rebind burst +${delayMs}ms")
                if (delayMs == HARMONY_REBIND_DELAYS_MS.last()) {
                    burstScheduled = false
                }
            }, delayMs)
        }
    }

    private fun recycleListenerComponent(context: Context) {
        if (componentRecycleInFlight) return
        componentRecycleInFlight = true
        val appContext = context.applicationContext
        val component = listenerComponent(appContext)
        val pm = appContext.packageManager
        HudLog.w("NLS: recycling listener component (Harmony)")
        runCatching {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }.onFailure { error ->
            HudLog.w("NLS component disable failed: ${error.message}")
            componentRecycleInFlight = false
            return
        }
        mainHandler.postDelayed({
            runCatching {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                HudLog.i("NLS component re-enabled")
                ensureKeepAlive(appContext, forceRestart = true)
                rebindListener(appContext)
                mainHandler.postDelayed({
                    if (!isUserListeningEnabled(appContext) || !isEnabled(appContext)) return@postDelayed
                    rebindListener(appContext)
                    HUDNotificationListenerService.refreshActiveNotifications("postRecycleRebind")
                    HudLog.i("NLS post-recycle rebind +${POST_RECYCLE_REBIND_MS}ms")
                }, POST_RECYCLE_REBIND_MS)
            }.onFailure { error ->
                HudLog.w("NLS component enable failed: ${error.message}")
            }
            componentRecycleInFlight = false
        }, COMPONENT_RECYCLE_DELAY_MS)
    }

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

    fun enable(context: Context): EnableResult {
        setUserListeningEnabled(context, true)
        if (!isEnabled(context)) {
            openAccessSettings(context)
            return EnableResult.OPENED_SETTINGS
        }
        beginHarmonyListening(context, recycleIfMissing = true, forceKeepAlive = true)
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
        HudListeningForegroundService.stop(context)
        burstScheduled = false
        keepAliveRecoveryDone = false
        missingServiceCount = 0
        HudRefreshScheduler.stop()
        NavEventHub.setYandexFullscreenPlaceholder(false)
        NavEventHub.resetLastGuidance()
        NavEventHub.publishConnection(false)
        NavEventHub.publishClear()
        NavOverlayHolder.dismissImmediate()
        val component = listenerComponent(context)
        if (NotificationListenerCompat.supportsUnbind) {
            NotificationListenerCompat.requestUnbind(component)
        } else {
            HudLog.i("disable: Harmony — unbind unavailable, processing stopped")
        }
    }

    fun cancelPendingRebind() {
        pendingRebind?.let { mainHandler.removeCallbacks(it) }
        pendingRebind = null
    }

    fun refreshBindingIfEnabled(context: Context) {
        if (isUserListeningEnabled(context) && isEnabled(context)) {
            forceReconnect(context)
        }
    }

    fun forceReconnect(context: Context) {
        if (!isUserListeningEnabled(context) || !isEnabled(context)) return
        ensureKeepAlive(context)

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
