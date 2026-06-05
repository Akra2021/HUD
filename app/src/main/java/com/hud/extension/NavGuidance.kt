package com.hud.extension

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

data class NavGuidance(
    /** Строка 1: дистанция до манёвра. */
    val instruction: String,
    /** Строка 2: улица / объект / камера / Exit и пр. */
    val detail: String,
    /** Строка 3: время до финиша | дистанция до финиша. */
    val routeSummaryText: String?,
    /** @deprecated строка 1 */
    val stepDistanceText: String? = instruction,
    /** @deprecated строка 3 */
    val arrivalTimeText: String? = routeSummaryText,
    /** @deprecated строка 3 */
    val timeText: String? = routeSummaryText,
    val distanceText: String? = routeSummaryText,
    val distanceMeters: Int?,
    val timeSeconds: Int?,
    @DrawableRes val maneuverIconRes: Int,
    val maneuverType: MapboxManeuverResolver.ManeuverType,
    @ColorInt val distanceTimerColor: Int,
    @ColorInt val timeTimerColor: Int,
    val notificationIcon: Bitmap?,
    /** Все уникальные строки из floating_window_notification (RemoteViews + extras). */
    val displayLines: List<String> = emptyList(),
    /** Иконка манёвра из RemoteViews уведомления. */
    val remoteManeuverIcon: Bitmap? = null,
    val isPlaceholder: Boolean,
    val detailScore: Int
) {
    fun contentEquals(other: NavGuidance): Boolean =
        instruction == other.instruction &&
            detail == other.detail &&
            routeSummaryText == other.routeSummaryText &&
            maneuverType == other.maneuverType

    fun hasDisplayableContent(): Boolean =
        instruction.isNotBlank() ||
            detail.isNotBlank() ||
            !routeSummaryText.isNullOrBlank() ||
            displayLines.any { it.isNotBlank() }
}

object NavTimerColors {
    @ColorInt
    fun forDistanceMeters(meters: Int?): Int = when {
        meters == null -> 0xFF455A64.toInt()
        meters < 300 -> 0xFFC62828.toInt()
        meters < 1000 -> 0xFFF9A825.toInt()
        else -> 0xFF2E7D32.toInt()
    }

    @ColorInt
    fun forTimeSeconds(seconds: Int?): Int = when {
        seconds == null -> 0xFF455A64.toInt()
        seconds < 180 -> 0xFFC62828.toInt()
        seconds < 600 -> 0xFFF9A825.toInt()
        else -> 0xFF2E7D32.toInt()
    }
}
