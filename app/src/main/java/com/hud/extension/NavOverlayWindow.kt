package com.hud.extension

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Overlay на Display ID 3 (эталон 800×480).
 * Окно 666×166 px, текст bold (÷2.5 от прежнего).
 */
class NavOverlayWindow(private val context: Context) {

    private val overlayContext: Context = resolveOverlayContext(context)
    private val windowManager =
        overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics: DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        overlayContext.display?.getRealMetrics(it)
            ?: overlayContext.resources.displayMetrics.let { resMetrics ->
                it.setTo(resMetrics)
            }
    }

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var maneuverIconView: ImageView? = null
    private var instructionView: TextView? = null
    private var detailView: TextView? = null
    private var routeSummaryView: TextView? = null

    private var overlayWidthPx = 0
    private var overlayHeightPx = 0
    private var screenWidthPx = 0
    private var screenHeightPx = 0

    fun show() {
        if (rootView != null) return

        screenWidthPx = displayMetrics.widthPixels
        screenHeightPx = displayMetrics.heightPixels
        overlayWidthPx = scaleFromReference(OVERLAY_WIDTH_PX, screenWidthPx, REF_SCREEN_WIDTH)
            .coerceAtMost(screenWidthPx)
        overlayHeightPx = scaleFromReference(OVERLAY_HEIGHT_PX, screenHeightPx, REF_SCREEN_HEIGHT)
            .coerceAtMost(screenHeightPx)

        HudLog.i(
            "overlay displayId=${overlayContext.display?.displayId} size=${screenWidthPx}x$screenHeightPx " +
                "window=${overlayWidthPx}x$overlayHeightPx"
        )

        val view = LayoutInflater.from(overlayContext).inflate(R.layout.overlay_nav_window, null)
        rootView = view

        maneuverIconView = view.findViewById(R.id.overlay_maneuver_icon)
        instructionView = view.findViewById(R.id.overlay_instruction)
        detailView = view.findViewById(R.id.overlay_detail)
        routeSummaryView = view.findViewById(R.id.overlay_route_summary)
        scaleOverlayContent(view)

        showWaiting()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            overlayWidthPx,
            overlayHeightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidthPx - overlayWidthPx) / 2f).roundToInt().coerceAtLeast(0)
            y = scaleFromReference(TOP_MARGIN_PX, screenHeightPx, REF_SCREEN_HEIGHT).coerceAtLeast(0)
        }

        try {
            windowManager.addView(view, layoutParams)
        } catch (e: Exception) {
            HudLog.e("overlay addView failed on display ${overlayContext.display?.displayId}", e)
            rootView = null
            layoutParams = null
        }
    }

    fun updateGuidance(guidance: NavGuidance) {
        val line1 = HudOverlayIcons.sanitizeLine(
            guidance.instruction.ifBlank { guidance.displayLines.getOrNull(0).orEmpty() }
        )
        val line2 = HudOverlayIcons.sanitizeLine(
            guidance.detail.ifBlank { guidance.displayLines.getOrNull(1).orEmpty() }
        )
        val line3 = HudOverlayIcons.sanitizeLine(
            guidance.routeSummaryText.orEmpty().ifBlank { guidance.displayLines.getOrNull(2).orEmpty() }
        )

        instructionView?.text = line1
        instructionView?.visibility = if (line1.isBlank()) View.GONE else View.VISIBLE

        detailView?.text = line2
        detailView?.visibility = if (line2.isBlank()) View.GONE else View.VISIBLE

        routeSummaryView?.text = line3
        routeSummaryView?.visibility = if (line3.isBlank()) View.GONE else View.VISIBLE

        applyManeuverIcon(guidance, line1, line2, line3)
    }

    private fun applyManeuverIcon(guidance: NavGuidance, line1: String, line2: String, line3: String) {
        val view = maneuverIconView ?: return
        when {
            HudOverlayIcons.isSpeedCameraAlert(line1, line2, line3) -> {
                view.setImageResource(HudOverlayIcons.speedCameraIcon)
                view.clearColorFilter()
                view.visibility = View.VISIBLE
            }
            guidance.remoteManeuverIcon != null -> {
                view.setImageBitmap(guidance.remoteManeuverIcon)
                view.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                view.visibility = View.VISIBLE
            }
            MapboxManeuverResolver.hasManeuverIcon(guidance.maneuverType) -> {
                view.setImageResource(guidance.maneuverIconRes)
                view.clearColorFilter()
                view.visibility = View.VISIBLE
            }
            else -> {
                view.setImageResource(HudOverlayIcons.defaultManeuverIcon)
                view.clearColorFilter()
                view.visibility = View.VISIBLE
            }
        }
    }

    fun showWaiting() {
        if (NavEventHub.yandexFullscreenPlaceholder) {
            instructionView?.text = overlayContext.getString(R.string.overlay_fullscreen_line1)
            instructionView?.visibility = View.VISIBLE
            detailView?.text = overlayContext.getString(R.string.overlay_fullscreen_line2)
            detailView?.visibility = View.VISIBLE
        } else {
            instructionView?.text = overlayContext.getString(R.string.overlay_waiting)
            instructionView?.visibility = View.VISIBLE
            detailView?.visibility = View.GONE
        }
        routeSummaryView?.visibility = View.GONE
        maneuverIconView?.setImageResource(HudOverlayIcons.defaultManeuverIcon)
        maneuverIconView?.clearColorFilter()
        maneuverIconView?.visibility = View.VISIBLE
    }

    fun dismiss() {
        rootView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                HudLog.e("overlay removeView failed", e)
            }
        }
        rootView = null
        layoutParams = null
        maneuverIconView = null
        instructionView = null
        detailView = null
        routeSummaryView = null
    }

    private fun scaleOverlayContent(root: View) {
        val contentScale = overlayWidthPx.toFloat() / CONTENT_REF_WIDTH_PX.toFloat()
        val textFactor = TEXT_SCALE / TEXT_SIZE_DIVISOR
        val iconSize = (BASE_ICON_PX * contentScale).roundToInt().coerceAtLeast(24)

        maneuverIconView?.layoutParams =
            (maneuverIconView?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply {
                width = iconSize
                height = iconSize
            }

        applyBoldText(instructionView, BASE_TITLE_PX * textFactor * contentScale)
        applyBoldText(detailView, BASE_BODY_PX * textFactor * contentScale)
        applyBoldText(routeSummaryView, BASE_META_PX * textFactor * contentScale)

        val padding = (10f * contentScale).roundToInt()
        root.setPadding(padding, padding, padding, padding)
    }

    private fun applyBoldText(view: TextView?, sizePx: Float) {
        view ?: return
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx.coerceAtLeast(8f))
        view.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    private fun scaleFromReference(valueOnRef: Int, actualScreen: Int, refScreen: Int): Int {
        return (valueOnRef.toFloat() * actualScreen / refScreen).roundToInt().coerceAtLeast(1)
    }

    private fun resolveOverlayContext(base: Context): Context {
        val displayManager = base.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(TARGET_DISPLAY_ID)
        if (display != null) {
            HudLog.i("overlay using display id=$TARGET_DISPLAY_ID")
            return base.createDisplayContext(display)
        }
        HudLog.w("display $TARGET_DISPLAY_ID not found, fallback to default display")
        return base
    }

    companion object {
        const val TARGET_DISPLAY_ID = 3
        const val REF_SCREEN_WIDTH = 800
        const val REF_SCREEN_HEIGHT = 480
        /** Было 666px (605 + 10%), уменьшаем на 15%. */
        const val OVERLAY_WIDTH_PX = 566
        const val OVERLAY_HEIGHT_PX = 166
        private const val TOP_MARGIN_PX = 90
        private const val CONTENT_REF_WIDTH_PX = 332

        private const val TEXT_SCALE = 2f
        private const val TEXT_SIZE_DIVISOR = 2.5f
        private const val BASE_TITLE_PX = 22f
        private const val BASE_BODY_PX = 16f
        private const val BASE_META_PX = 13f
        private const val BASE_ICON_PX = 72f
    }
}
