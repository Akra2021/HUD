package com.hud.extension

import android.content.Context

/**
 * On-device indicator sources: logcat VHAL → reflection API → dumpsys → manual buttons.
 */
class VehicleIndicatorHub(
    private val onStateChanged: (leftOn: Boolean, rightOn: Boolean) -> Unit
) {

    private val sources = listOf(
        ShellLogcatVehicleSource(),
        ReflectionVehicleSource(),
        ShellDumpsysVehicleSource(),
    )
    private val manualSource = ManualVehicleSource()

    private var activeSource: VehicleIndicatorSource? = null
    var activeSourceName: String? = null
        private set

    val isManualMode: Boolean
        get() = activeSource === manualSource

    fun start(context: Context, allowManualFallback: Boolean = true): Boolean {
        stop()
        for (source in sources) {
            if (source.start(context, onStateChanged)) {
                activeSource = source
                activeSourceName = source.name
                HudLog.i("turn-signal hub using ${source.name}")
                return true
            }
        }
        if (allowManualFallback && manualSource.start(context, onStateChanged)) {
            activeSource = manualSource
            activeSourceName = manualSource.name
            HudLog.w("turn-signal hub: manual fallback enabled")
            return true
        }
        HudLog.w("turn-signal hub: no source available")
        return false
    }

    fun stop() {
        activeSource?.stop()
        activeSource = null
        activeSourceName = null
    }

    fun restart(context: Context, allowManualFallback: Boolean = true): Boolean =
        start(context, allowManualFallback)

    fun triggerLeft(on: Boolean) {
        (activeSource as? ManualVehicleSource)?.setLeft(on)
    }

    fun triggerRight(on: Boolean) {
        (activeSource as? ManualVehicleSource)?.setRight(on)
    }

    fun clearManual() {
        (activeSource as? ManualVehicleSource)?.clear()
    }
}
