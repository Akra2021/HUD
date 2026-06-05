package com.hud.extension

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import java.lang.reflect.Method

/**
 * Harmony OS не имеет NotificationListenerService.requestUnbind(ComponentName).
 * Вызываем rebind/unbind через reflection с безопасным fallback.
 */
object NotificationListenerCompat {

    private val requestUnbindMethod: Method? by lazy {
        runCatching {
            NotificationListenerService::class.java.getMethod(
                "requestUnbind",
                ComponentName::class.java
            )
        }.getOrNull()
    }

    private val requestRebindMethod: Method? by lazy {
        runCatching {
            NotificationListenerService::class.java.getMethod(
                "requestRebind",
                ComponentName::class.java
            )
        }.getOrNull()
    }

    val supportsUnbind: Boolean get() = requestUnbindMethod != null
    val supportsRebind: Boolean get() = requestRebindMethod != null

    fun requestUnbind(component: ComponentName): Boolean {
        val method = requestUnbindMethod ?: return false
        return invoke(method, component, "requestUnbind")
    }

    fun requestRebind(component: ComponentName): Boolean {
        val method = requestRebindMethod ?: return false
        return invoke(method, component, "requestRebind")
    }

    private fun invoke(method: Method, component: ComponentName, name: String): Boolean {
        return runCatching {
            method.invoke(null, component)
            HudLog.i("$name ok")
            true
        }.getOrElse { error ->
            HudLog.w("$name failed: ${error.message}")
            false
        }
    }
}
