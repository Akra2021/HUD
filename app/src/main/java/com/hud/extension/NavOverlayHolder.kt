package com.hud.extension

import android.os.Handler
import android.os.Looper

/** Overlay на Display 3 — обновляется из сервисов, не только из Activity. */
object NavOverlayHolder {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var overlay: NavOverlayWindow? = null
        private set

    fun attach(overlay: NavOverlayWindow) {
        this.overlay = overlay
    }

    fun detach() {
        overlay = null
    }

    fun applyGuidance(guidance: NavGuidance, context: android.content.Context) {
        if (!NotificationAccessHelper.isUserListeningEnabled(context)) return
        mainHandler.post {
            if (!NotificationAccessHelper.isUserListeningEnabled(context)) return@post
            overlay?.updateGuidance(guidance)
        }
    }

    fun showWaiting() {
        mainHandler.post {
            overlay?.showWaiting()
        }
    }

    fun dismiss() {
        mainHandler.post {
            overlay?.dismiss()
            overlay = null
        }
    }
}
