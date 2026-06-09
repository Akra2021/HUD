package com.hud.extension

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Creates text overlay on Display 3 when MainActivity is not in foreground. */
object HudOverlayBootstrap {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensureShown(context: Context) {
        if (!HudPreferences.usesNotificationNavPath(context)) return
        if (!Settings.canDrawOverlays(context)) return
        runOnMain {
            if (NavOverlayHolder.overlay != null) return@runOnMain
            runCatching {
                NavOverlayWindow(context.applicationContext).also { overlay ->
                    overlay.show()
                    NavOverlayHolder.attach(overlay)
                    HudLog.i("overlay bootstrapped on display ${NavOverlayWindow.TARGET_DISPLAY_ID}")
                }
            }.onFailure { error ->
                HudLog.e("overlay bootstrap failed", error)
            }
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
