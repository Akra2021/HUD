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
        runOnMain {
            if (!NotificationAccessHelper.isUserListeningEnabled(context)) return@runOnMain
            overlay?.updateGuidance(guidance)
        }
    }

    fun showWaiting() {
        runOnMain {
            if (!isHudListening()) return@runOnMain
            overlay?.showWaiting()
        }
    }

    fun dismiss() {
        runOnMain {
            dismissImmediate()
        }
    }

    fun dismissImmediate() {
        overlay?.dismiss()
        overlay = null
        NavOverlayWindow.dismissAny()
    }

    private fun isHudListening(): Boolean {
        val ctx = NotificationAccessHelper.getAppContext() ?: return false
        return NotificationAccessHelper.isUserListeningEnabled(ctx)
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
