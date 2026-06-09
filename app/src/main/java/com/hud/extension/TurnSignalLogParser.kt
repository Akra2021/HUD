package com.hud.extension

/**
 * Parses Harmony D587 / VHAL / Tuanjie log lines for turn indicators.
 *
 * Examples:
 * - VHAL: path=Vehicle.Body.Lights.IsRightIndicatorOn, Read Value: 1
 * - Tuanjie: TurnRightLightListener => RightIndicator displayId -> 1, value -> 1
 * - D587_Vehicle: group = turnRightLight, key = turnLeftLight, value = 1
 */
object TurnSignalLogParser {

    data class State(val leftOn: Boolean, val rightOn: Boolean)

    data class Parsed(
        val leftOn: Boolean = false,
        val rightOn: Boolean = false,
        val sawLeft: Boolean = false,
        val sawRight: Boolean = false,
    ) {
        fun hasSignal(): Boolean = sawLeft || sawRight

        fun mergeWithPrevious(previousLeft: Boolean, previousRight: Boolean): State = State(
            leftOn = if (sawLeft) leftOn else previousLeft,
            rightOn = if (sawRight) rightOn else previousRight
        )
    }

    private val vhalLeft = Regex("""IsLeftIndicatorOn,\s*Read Value:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val vhalRight = Regex("""IsRightIndicatorOn,\s*Read Value:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val tuanjieLeft = Regex("""TurnLeftLightListener.*?value\s*->\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val tuanjieRight = Regex("""TurnRightLightListener.*?value\s*->\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val kanziLeft = Regex("""group\s*=\s*turnLeftLight.*?value\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val kanziRight = Regex("""group\s*=\s*turnRightLight.*?value\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): State = parseWithPresence(text)?.let {
        State(it.leftOn, it.rightOn)
    } ?: State(false, false)

    fun parseWithPresence(text: String): Parsed? {
        var leftOn = false
        var rightOn = false
        var sawLeft = false
        var sawRight = false

        for (line in text.lineSequence()) {
            when {
                line.contains("IsLeftIndicatorOn", ignoreCase = true) -> {
                    vhalLeft.find(line)?.groupValues?.getOrNull(1)?.let {
                        leftOn = it == "1"
                        sawLeft = true
                    }
                }
                line.contains("IsRightIndicatorOn", ignoreCase = true) -> {
                    vhalRight.find(line)?.groupValues?.getOrNull(1)?.let {
                        rightOn = it == "1"
                        sawRight = true
                    }
                }
                line.contains("TurnLeftLightListener", ignoreCase = true) -> {
                    tuanjieLeft.find(line)?.groupValues?.getOrNull(1)?.let {
                        leftOn = it == "1"
                        sawLeft = true
                    }
                }
                line.contains("TurnRightLightListener", ignoreCase = true) -> {
                    tuanjieRight.find(line)?.groupValues?.getOrNull(1)?.let {
                        rightOn = it == "1"
                        sawRight = true
                    }
                }
                line.contains("group = turnLeftLight", ignoreCase = true) -> {
                    kanziLeft.find(line)?.groupValues?.getOrNull(1)?.let {
                        leftOn = it == "1"
                        sawLeft = true
                    }
                }
                line.contains("group = turnRightLight", ignoreCase = true) -> {
                    kanziRight.find(line)?.groupValues?.getOrNull(1)?.let {
                        rightOn = it == "1"
                        sawRight = true
                    }
                }
            }
        }

        if (!sawLeft && !sawRight) return null
        return Parsed(leftOn, rightOn, sawLeft, sawRight)
    }
}
