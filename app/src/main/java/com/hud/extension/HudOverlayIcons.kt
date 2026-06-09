package com.hud.extension

import androidx.annotation.DrawableRes

object HudOverlayIcons {

    /** Chevron «вперёд» — когда нет иконки из Яндекса и тип манёвра не распознан по тексту. */
    @DrawableRes
    val navFallbackIcon: Int = R.drawable.ic_nav_forward_fallback

    @DrawableRes
    val defaultManeuverIcon: Int = navFallbackIcon

    @DrawableRes
    val speedCameraIcon: Int = R.drawable.ic_speed_camera

    fun hasResolvedManeuverIcon(guidance: NavGuidance): Boolean =
        guidance.notificationIcon != null ||
            guidance.remoteManeuverIcon != null ||
            MapboxManeuverResolver.hasManeuverIcon(guidance.maneuverType)

    fun shouldShowNavFallbackIcon(
        guidance: NavGuidance,
        line1: String,
        line2: String,
        line3: String
    ): Boolean =
        guidance.hasDisplayableContent() &&
            !isSpeedCameraAlert(line1, line2, line3) &&
            !hasResolvedManeuverIcon(guidance)

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
            normalized.contains("complete the route") ||
            normalized.contains("complete route") ||
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
