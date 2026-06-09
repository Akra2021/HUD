package com.hud.extension

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TurnSignalCameraController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)

    private var hub: VehicleIndicatorHub? = null
    private var mirrorWindow: TurnSignalMirrorWindow? = null

    private var monitoringEnabled = false
    private var leftOn = false
    private var rightOn = false
    private var refreshScheduled = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshScheduled = false
            if (!monitoringEnabled || (!leftOn && !rightOn)) return
            captureAndShow("refresh")
            if (monitoringEnabled && (leftOn || rightOn)) {
                refreshScheduled = true
                mainHandler.postDelayed(this, REFRESH_MS)
            }
        }
    }

    fun startMonitoring(): Boolean {
        if (monitoringEnabled) return true
        if (!android.provider.Settings.canDrawOverlays(context)) {
            onStatus(context.getString(R.string.turn_signal_overlay_required))
            return false
        }

        val indicatorHub = VehicleIndicatorHub(::handleIndicatorState)
        if (!indicatorHub.start(context, allowManualFallback = true)) {
            onStatus(context.getString(R.string.turn_signal_vehicle_unavailable))
            return false
        }

        hub = indicatorHub
        mirrorWindow = TurnSignalMirrorWindow(context.applicationContext)
        monitoringEnabled = true
        onStatus(statusForSource(indicatorHub))
        return true
    }

    fun stopMonitoring() {
        monitoringEnabled = false
        leftOn = false
        rightOn = false
        mainHandler.removeCallbacks(refreshRunnable)
        refreshScheduled = false
        hub?.stop()
        hub = null
        mirrorWindow?.dismiss()
        mirrorWindow = null
        onStatus(context.getString(R.string.turn_signal_stopped))
    }

    fun retryMonitoring(): Boolean {
        if (!monitoringEnabled) return startMonitoring()
        val indicatorHub = hub ?: return startMonitoring()
        if (!indicatorHub.restart(context, allowManualFallback = true)) {
            onStatus(context.getString(R.string.turn_signal_vehicle_unavailable))
            return false
        }
        onStatus(statusForSource(indicatorHub))
        return true
    }

    fun captureOnceForTest() {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            onStatus(context.getString(R.string.turn_signal_overlay_required))
            return
        }
        if (mirrorWindow == null) {
            mirrorWindow = TurnSignalMirrorWindow(context.applicationContext)
        }
        captureAndShow("manual")
    }

    fun triggerLeftIndicator(on: Boolean) {
        hub?.triggerLeft(on)
    }

    fun triggerRightIndicator(on: Boolean) {
        hub?.triggerRight(on)
    }

    fun isMonitoring(): Boolean = monitoringEnabled

    fun isManualMode(): Boolean = hub?.isManualMode == true

    fun release() {
        stopMonitoring()
        captureExecutor.shutdownNow()
    }

    private fun statusForSource(hub: VehicleIndicatorHub): String = when (hub.activeSourceName) {
        "manual" -> context.getString(R.string.turn_signal_manual_mode)
        "shell-logcat" -> context.getString(R.string.turn_signal_logcat_mode)
        "shell-dumpsys" -> context.getString(R.string.turn_signal_dumpsys_mode)
        else -> context.getString(R.string.turn_signal_monitoring)
    }

    private fun handleIndicatorState(left: Boolean, right: Boolean) {
        val wasActive = leftOn || rightOn
        leftOn = left
        rightOn = right
        val isActive = leftOn || rightOn

        onStatus(
            when {
                leftOn && rightOn -> context.getString(R.string.turn_signal_both_on)
                leftOn -> context.getString(R.string.turn_signal_left_on)
                rightOn -> context.getString(R.string.turn_signal_right_on)
                hub?.isManualMode == true -> context.getString(R.string.turn_signal_manual_mode)
                else -> statusForSource(hub ?: return)
            }
        )

        if (isActive && !wasActive) {
            captureAndShow("edge-on")
            scheduleRefreshLoop()
        } else if (!isActive && wasActive) {
            mainHandler.removeCallbacks(refreshRunnable)
            refreshScheduled = false
            mainHandler.post { mirrorWindow?.hide() }
        }
    }

    private fun scheduleRefreshLoop() {
        if (refreshScheduled || !monitoringEnabled) return
        refreshScheduled = true
        mainHandler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    private fun captureAndShow(reason: String) {
        if (!monitoringEnabled && reason != "manual") return
        if (!capturing.compareAndSet(false, true)) return

        captureExecutor.execute {
            val bitmap = runCatching {
                CameraRegionCapture.captureCropAndScale()
            }.getOrNull()

            mainHandler.post {
                capturing.set(false)
                if (bitmap == null) {
                    if (reason == "manual" || reason == "edge-on") {
                        onStatus(context.getString(R.string.turn_signal_capture_failed))
                    } else {
                        HudLog.w("turn-signal capture failed ($reason)")
                    }
                    return@post
                }

                if (reason == "manual" || leftOn || rightOn) {
                    mirrorWindow?.show(bitmap)
                    if (reason == "manual") {
                        onStatus(context.getString(R.string.turn_signal_capture_ok))
                    }
                } else {
                    bitmap.recycle()
                }
            }
        }
    }

    companion object {
        private const val REFRESH_MS = 200L
    }
}
