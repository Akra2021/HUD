package com.hud.extension

import android.util.Log

/**
 * Единый тег для logcat.
 * zsh: `adb logcat HUD:I '*:S'`  (кавычки обязательны)
 * или: `adb logcat -s HUD`
 */
object HudLog {
    const val TAG = "HUD"

    fun i(message: String) = Log.i(TAG, message)
    fun d(message: String) = Log.d(TAG, message)
    fun w(message: String) = Log.w(TAG, message)
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
