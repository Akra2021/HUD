package com.hud.extension

import androidx.annotation.DrawableRes

/**
 * Иконки Mapbox Navigation + распознавание манёвров (RU/EN).
 */
object MapboxManeuverResolver {

    enum class ManeuverType {
        TURN_LEFT,
        TURN_RIGHT,
        SLIGHT_LEFT,
        SLIGHT_RIGHT,
        SHARP_LEFT,
        SHARP_RIGHT,
        U_TURN,
        ROUNDABOUT,
        MERGE_LEFT,
        MERGE_RIGHT,
        OFF_RAMP_LEFT,
        OFF_RAMP_RIGHT,
        FORK_LEFT,
        FORK_RIGHT,
        CONTINUE_STRAIGHT,
        ARRIVE,
        DEPART,
        UNKNOWN
    }

    private val GENERIC_MANEUVER_REGEXES = listOf(
        Regex("""^go\s+(straight|right|left|ahead|forward)$""", RegexOption.IGNORE_CASE),
        Regex("""^turn\s+(right|left)$""", RegexOption.IGNORE_CASE),
        Regex("""^turn\s+(slight|sharp)\s+(right|left)$""", RegexOption.IGNORE_CASE),
        Regex("""^bear\s+(right|left)$""", RegexOption.IGNORE_CASE),
        Regex("""^keep\s+(right|left)$""", RegexOption.IGNORE_CASE),
        Regex("""^continue\s+(straight|on)?$""", RegexOption.IGNORE_CASE),
        Regex("""^head\s+(north|south|east|west)$""", RegexOption.IGNORE_CASE),
        Regex("""^(straight|forward|ahead)$""", RegexOption.IGNORE_CASE),
        Regex("""^направо$""", RegexOption.IGNORE_CASE),
        Regex("""^налево$""", RegexOption.IGNORE_CASE),
        Regex("""^прямо$""", RegexOption.IGNORE_CASE),
        Regex("""^(поверните|сверните|поворот)\s+(налево|направо|на\s+лево|на\s+право).*$""", RegexOption.IGNORE_CASE),
        Regex("""^(держитесь|keep)\s+(левее|правее|left|right).*$""", RegexOption.IGNORE_CASE),
        Regex("""^go\s+right\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^go\s+left\.?$""", RegexOption.IGNORE_CASE)
    )

    @DrawableRes
    fun iconFor(type: ManeuverType): Int = when (type) {
        ManeuverType.TURN_LEFT -> R.drawable.mapbox_ic_turn_left
        ManeuverType.TURN_RIGHT -> R.drawable.mapbox_ic_turn_right
        ManeuverType.SLIGHT_LEFT -> R.drawable.mapbox_ic_turn_slight_left
        ManeuverType.SLIGHT_RIGHT -> R.drawable.mapbox_ic_turn_slight_right
        ManeuverType.SHARP_LEFT -> R.drawable.mapbox_ic_turn_sharp_left
        ManeuverType.SHARP_RIGHT -> R.drawable.mapbox_ic_turn_sharp_right
        ManeuverType.U_TURN -> R.drawable.mapbox_ic_uturn
        ManeuverType.ROUNDABOUT -> R.drawable.mapbox_ic_roundabout
        ManeuverType.MERGE_LEFT -> R.drawable.mapbox_ic_merge_left
        ManeuverType.MERGE_RIGHT -> R.drawable.mapbox_ic_merge_right
        ManeuverType.OFF_RAMP_LEFT -> R.drawable.mapbox_ic_off_ramp_left
        ManeuverType.OFF_RAMP_RIGHT -> R.drawable.mapbox_ic_off_ramp_right
        ManeuverType.FORK_LEFT -> R.drawable.mapbox_ic_fork_left
        ManeuverType.FORK_RIGHT -> R.drawable.mapbox_ic_fork_right
        ManeuverType.ARRIVE -> R.drawable.mapbox_ic_arrive
        ManeuverType.DEPART -> R.drawable.mapbox_ic_depart_straight
        ManeuverType.CONTINUE_STRAIGHT -> R.drawable.mapbox_ic_turn_straight
        ManeuverType.UNKNOWN -> R.drawable.mapbox_ic_turn_straight
    }

    fun hasManeuverIcon(type: ManeuverType): Boolean = type != ManeuverType.UNKNOWN

    fun isGenericManeuverLabel(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return true
        return GENERIC_MANEUVER_REGEXES.any { it.matches(normalized) }
            || isGenericManeuverFragment(normalized.lowercase())
    }

    private fun isGenericManeuverFragment(lower: String): Boolean {
        val exact = setOf(
            "go right", "go left", "go straight", "go ahead", "go forward",
            "turn right", "turn left", "bear right", "bear left",
            "keep right", "keep left", "continue straight", "straight ahead",
            "направо", "налево", "прямо", "движение прямо"
        )
        return lower.trimEnd('.', '!', '?', '…') in exact
    }

    fun detectManeuver(vararg texts: String): ManeuverType {
        for (text in texts) {
            val line = normalizeLine(text)
            if (line.isEmpty()) continue
            detectFromBlob(line)?.let { return it }
        }
        return detectFromBlob(normalizeLine(texts.joinToString(" "))) ?: ManeuverType.UNKNOWN
    }

    private fun normalizeLine(text: String): String =
        text.trim().lowercase().trimEnd('.', '!', '?', '…')

    private fun detectFromBlob(blob: String): ManeuverType? = when {
        containsAny(blob, "разворот", "u-turn", "uturn", "u turn", "развернитесь") ->
            ManeuverType.U_TURN
        containsAny(blob, "кругов", "roundabout", "rotary", "на кольц", "кольцо") ->
            ManeuverType.ROUNDABOUT
        containsAny(blob, "slight right", "bear right", "слегка направо", "слегка прав",
            "держитесь правее", "keep right", "keep to the right") ->
            ManeuverType.SLIGHT_RIGHT
        containsAny(blob, "slight left", "bear left", "слегка налево", "слегка лев",
            "держитесь левее", "keep left", "keep to the left") ->
            ManeuverType.SLIGHT_LEFT
        containsAny(blob, "sharp right", "резко направо", "резкий поворот направо") ->
            ManeuverType.SHARP_RIGHT
        containsAny(blob, "sharp left", "резко налево", "резкий поворот налево") ->
            ManeuverType.SHARP_LEFT
        matchesGoDirection(blob, "right") || containsAny(blob, "turn right", "right turn", "направо",
            "поверните направо", "поворот направо", "сверните направо", "на право") ->
            ManeuverType.TURN_RIGHT
        matchesGoDirection(blob, "left") || containsAny(blob, "turn left", "left turn", "налево",
            "поверните налево", "поворот налево", "сверните налево", "на лево") ->
            ManeuverType.TURN_LEFT
        containsAny(blob, "слиян", "merge", "присоедин") -> when {
            containsAny(blob, "лев", "left") -> ManeuverType.MERGE_LEFT
            else -> ManeuverType.MERGE_RIGHT
        }
        containsAny(blob, "съезд", "выезд", "off-ramp", "off ramp", "exit", "exit ramp") -> when {
            containsAny(blob, "лев", "left") -> ManeuverType.OFF_RAMP_LEFT
            else -> ManeuverType.OFF_RAMP_RIGHT
        }
        containsAny(blob, "развилк", "fork") -> when {
            containsAny(blob, "лев", "left") -> ManeuverType.FORK_LEFT
            else -> ManeuverType.FORK_RIGHT
        }
        containsAny(blob, "прибыт", "приехали", "arrive", "arrival", "destination",
            "пункт назначения", "вы прибыли", "you have arrived") ->
            ManeuverType.ARRIVE
        containsAny(blob, "отправ", "depart", "начните", "start navigation") ->
            ManeuverType.DEPART
        matchesGoDirection(blob, "straight", "ahead", "forward") ||
            containsAny(blob, "straight ahead", "continue straight", "continue on", "прямо",
                "продолжайте", "движение прямо", "head straight") ->
            ManeuverType.CONTINUE_STRAIGHT
        else -> null
    }

    private fun matchesGoDirection(blob: String, vararg directions: String): Boolean =
        directions.any { direction ->
            Regex("""\bgo\s+$direction\b""").containsMatchIn(blob) ||
                Regex("""\bgo\s+$direction\.?$""").matches(blob)
        }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }
}
