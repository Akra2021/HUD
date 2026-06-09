package com.hud.extension

import java.util.regex.Pattern

/** Shared rules for HUD line 2 (street / exit / road) vs line 3 (route time | distance). */
object NavLineRules {

    private val ROAD_NAME_PATTERN = Pattern.compile(
        """\b([AMЕМА]-?\d{1,4}[A-ZА-Я]?)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val DISTANCE_VALUE = Pattern.compile(
        """(\d+[.,]?\d*)\s*(km|m|км|м)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val DISTANCE_ONLY = Pattern.compile(
        """^\s*(\d+[.,]?\d*)\s*(km|m|км|м)\.?\s*$""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    fun duplicatesRouteSummary(line2: String, line3: String?): Boolean {
        if (line3.isNullOrBlank() || line2.isBlank()) return false
        val key2 = normalizeKey(line2)
        val key3 = normalizeKey(line3)
        if (key2 == key3) return true
        val parts3 = line3.split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (parts3.any { normalizeKey(it) == key2 }) return true
        if (line2.contains("|") && parts3.size >= 2) {
            val parts2 = line2.split("|").map { it.trim() }.filter { it.isNotBlank() }
            if (parts2.isNotEmpty() && parts2.all { p -> parts3.any { normalizeKey(it) == normalizeKey(p) } }) {
                return true
            }
        }
        return false
    }

    fun matchesRouteMeta(line: String, routeDistLabel: String?, routeTimeLabel: String?): Boolean {
        val key = normalizeKey(line)
        if (key.isEmpty()) return false
        routeDistLabel?.takeIf { normalizeKey(it) == key }?.let { return true }
        routeTimeLabel?.takeIf { normalizeKey(it) == key }?.let { return true }
        return false
    }

    fun isRoadNameLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (ROAD_NAME_PATTERN.matcher(trimmed).find()) return true
        val lower = trimmed.lowercase()
        return lower.contains("motorway") || lower.contains("freeway") ||
            lower.contains("expressway") || lower.contains("highway") ||
            lower.contains("autobahn") || lower.contains("autostrada") ||
            (lower.contains("трасса") && !lower.contains("осталось")) ||
            (lower.contains("шоссе") && ROAD_NAME_PATTERN.matcher(trimmed).find())
    }

    fun normalizeKey(line: String): String =
        FloatingWindowNotificationDecoder.normalizeKey(line)

    /** Normalize distance units: "10 м" / "10m" / "10 m" → comparable form. */
    fun normalizeDistanceText(line: String): String {
        var text = normalizeKey(line)
            .replace('м', 'm')
            .replace("км", "km")
        text = text.replace(Regex("""(\d+[.,]?\d*)\s+([km])"""), "$1$2")
        return text
    }

    fun extractDistanceMeters(line: String): Int? {
        val matcher = DISTANCE_VALUE.matcher(normalizeDistanceText(line))
        if (!matcher.find()) return null
        val value = matcher.group(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val unit = matcher.group(2) ?: return null
        return when (unit) {
            "km" -> (value * 1000).toInt()
            "m" -> value.toInt()
            else -> null
        }
    }

    /** True when the whole line is only a step/route distance (e.g. "10 m", "10m", "10 м"). */
    fun isDistanceOnlyLine(line: String): Boolean {
        val norm = normalizeDistanceText(line.trim())
        if (DISTANCE_ONLY.matcher(norm).matches()) return true
        return extractDistanceMeters(line) != null &&
            line.trim().length <= 12 &&
            !line.contains("|")
    }

    /** Compare distance lines ignoring spaces and cyrillic/latin units. */
    fun duplicatesLine1(line1: String, line2: String): Boolean {
        if (line2.isBlank() || line1.isBlank()) return false
        if (normalizeKey(line1) == normalizeKey(line2)) return true
        val norm1 = normalizeDistanceText(line1)
        val norm2 = normalizeDistanceText(line2)
        if (norm1.isNotEmpty() && norm1 == norm2) return true
        val m1 = extractDistanceMeters(line1)
        val m2 = extractDistanceMeters(line2)
        return m1 != null && m2 != null && m1 == m2
    }

    /** Line 2 for HUD: street/exit only — never distance, never duplicate of line 1. */
    fun cleanDetailForHud(line1: String, line2: String, line3: String?, logSource: String? = null): String {
        val detail = line2.trim()
        if (detail.isEmpty()) return ""
        val reason = when {
            duplicatesLine1(line1, detail) -> "dup_line1"
            isDistanceOnlyLine(detail) -> "distance_only"
            duplicatesRouteSummary(detail, line3) -> "dup_line3"
            isNotificationActionLine(detail) -> "action_label"
            else -> null
        }
        if (reason != null) {
            logSource?.let {
                HudLog.dgis("cleanDetail ($it): drop line2='$detail' reason=$reason line1='$line1'")
            }
            return ""
        }
        return detail
    }

    private val NOTIFICATION_ACTION_PHRASES = setOf(
        "complete the route",
        "complete route",
        "finish the route",
        "finish route",
        "finish the root",
        "end navigation",
        "stop navigation",
        "завершить маршрут",
        "закончить маршрут",
        "завершить навигацию",
        "закончить навигацию"
    )

    private val NOTIFICATION_UI_LABELS = setOf(
        "open",
        "открыть",
        "launch",
        "expand",
        "collapse",
        "more",
        "details",
        "detail"
    )

    /** 2GIS / nav apps: action button labels inside RemoteViews, not street names. */
    fun isNotificationActionLine(line: String): Boolean {
        val norm = line.trim().lowercase().trimEnd('.', '!', '?', '…')
        if (norm.isEmpty()) return false
        if (norm in NOTIFICATION_UI_LABELS) return true
        if (norm in NOTIFICATION_ACTION_PHRASES) return true
        if (norm.startsWith("complete ") && norm.contains("route")) return true
        if (norm.startsWith("finish ") && (norm.contains("route") || norm.contains("root"))) return true
        return false
    }
}
