package com.hud.extension

import androidx.annotation.DrawableRes

object HudOverlayIcons {

    @DrawableRes
    val defaultManeuverIcon: Int = R.drawable.mapbox_ic_turn_straight

    @DrawableRes
    val speedCameraIcon: Int = R.drawable.ic_speed_camera

    fun sanitizeLine(line: String): String =
        if (isFinishRouteMessage(line)) "" else line.trim()

    fun isFinishRouteMessage(line: String): Boolean {
        val normalized = line.trim()
            .trimEnd('.', '!', '?', '…')
            .lowercase()
        if (normalized.isEmpty()) return false
        return normalized == "finish the route" ||
            normalized.contains("finish route") ||
            normalized.contains("finish the route") ||
            normalized.contains("завершите маршрут") ||
            normalized.contains("закончите маршрут")
    }

    fun isSpeedCameraAlert(vararg lines: String): Boolean =
        lines.any { isSpeedCameraMessage(it) }

    fun isSpeedCameraMessage(line: String): Boolean {
        val lower = line.trim().lowercase().trimEnd('.', '!', '?', '…')
        if (lower.isEmpty()) return false
        return lower.contains("speed camera") ||
            lower.contains("speed trap") ||
            lower.contains("камера скорост") ||
            lower.contains("радар") ||
            lower.contains("radar") ||
            (lower.contains("camera") && !lower.contains("profile")) ||
            (lower.contains("камер") && !lower.contains("profile"))
    }
}
