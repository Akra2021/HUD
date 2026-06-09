package com.hud.extension

import android.util.Log

/**
 * Единый тег для logcat.
 * Глубокий лог 2GIS + HUD:
 *   adb logcat -c && adb logcat -v threadtime HUD:I HUD_2GIS:I '*:S'
 */
object HudLog {
    const val TAG = "HUD"
    const val TAG_2GIS = "HUD_2GIS"

    fun i(message: String) = Log.i(TAG, message)
    fun d(message: String) = Log.d(TAG, message)
    fun w(message: String) = Log.w(TAG, message)
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }

    fun dgis(message: String) = Log.i(TAG_2GIS, message)
}
