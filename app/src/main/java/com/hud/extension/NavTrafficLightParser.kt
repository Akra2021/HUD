package com.hud.extension

/**
 * Yandex Navigator traffic-light countdown (1–999 s) from floating notification texts.
 * Usually a standalone 1–3 digit line, e.g. "14" or "120".
 */
object NavTrafficLightParser {

    private val STANDALONE_SECONDS = Regex("""^\d{1,3}$""")
    private val LABELED_SECONDS = Regex(
        """^(?:через\s+)?(\d{1,3})\s*(?:с|сек\.?|sec\.?|s)?\.?$""",
        RegexOption.IGNORE_CASE
    )

    private const val MAX_SECONDS = 999
    private const val TYPICAL_MAX = 180

    fun extractSeconds(lines: List<String>, usedLines: Collection<String>): Int? {
        val usedValues = collectUsedValues(usedLines)
        val candidates = linkedSetOf<Int>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (NavLineRules.isDistanceOnlyLine(line)) continue
            if (isRouteTimeLine(line)) continue

            STANDALONE_SECONDS.matchEntire(line)?.let {
                line.toIntOrNull()?.let { value -> candidates.add(value) }
            }
            LABELED_SECONDS.matchEntire(line)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { value -> candidates.add(value) }
            }
        }

        val filtered = candidates.filter { value ->
            value in 1..MAX_SECONDS && value !in usedValues
        }
        if (filtered.isEmpty()) return null

        return filtered.filter { it <= TYPICAL_MAX }.minOrNull()
            ?: filtered.minOrNull()
    }

    private fun collectUsedValues(lines: Collection<String>): Set<Int> {
        val used = linkedSetOf<Int>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            NavLineRules.extractDistanceMeters(trimmed)?.let { used.add(it) }
            STANDALONE_SECONDS.matchEntire(trimmed)?.let {
                trimmed.toIntOrNull()?.let { value -> used.add(value) }
            }
            Regex("""(\d+)""").findAll(trimmed).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let { used.add(it) }
            }
        }
        return used
    }

    private fun isRouteTimeLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("мин") || lower.contains("min") ||
            lower.contains("ч ") || lower.contains("час") ||
            lower.contains("hour") || Regex("""\d{1,2}:\d{2}""").containsMatchIn(line)
    }
}
