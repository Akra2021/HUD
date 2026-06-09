package com.hud.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/** Full-screen camera mirror on Display 3 (800×480). */
class TurnSignalMirrorWindow(private val context: Context) {

    private val overlayContext: Context = resolveOverlayContext(context)
    private val windowManager =
        overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics: DisplayMetrics = DisplayMetrics().also { metrics ->
        @Suppress("DEPRECATION")
        overlayContext.display?.getRealMetrics(metrics)
            ?: metrics.setTo(overlayContext.resources.displayMetrics)
    }

    private var rootView: View? = null
    private var imageView: ImageView? = null

    fun show(bitmap: Bitmap) {
        ensureView()
        imageView?.setImageBitmap(bitmap)
        rootView?.visibility = View.VISIBLE
    }

    fun hide() {
        rootView?.visibility = View.GONE
        imageView?.setImageBitmap(null)
    }

    fun dismiss() {
        hide()
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
                .onFailure { error -> HudLog.e("turn-signal mirror removeView failed", error) }
        }
        rootView = null
        imageView = null
        if (activeInstance === this) {
            activeInstance = null
        }
    }

    private fun ensureView() {
        if (rootView != null) return

        val view = LayoutInflater.from(overlayContext)
            .inflate(R.layout.overlay_turn_signal_mirror, null, false)
        imageView = view.findViewById(R.id.turn_signal_mirror_image)

        val params = WindowManager.LayoutParams(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(view, params)
            rootView = view
            activeInstance = this
            HudLog.i("turn-signal mirror shown on display ${overlayContext.display?.displayId}")
        } catch (error: Exception) {
            HudLog.e("turn-signal mirror addView failed", error)
            rootView = null
            imageView = null
        }
    }

    private fun resolveOverlayContext(base: Context): Context {
        val displayManager = base.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(NavOverlayWindow.TARGET_DISPLAY_ID)
        if (display != null) {
            return base.createDisplayContext(display)
        }
        HudLog.w("turn-signal display ${NavOverlayWindow.TARGET_DISPLAY_ID} not found, using default")
        return base
    }

    companion object {
        @Volatile
        private var activeInstance: TurnSignalMirrorWindow? = null

        fun dismissAny() {
            activeInstance?.dismiss()
            activeInstance = null
        }
    }
}
