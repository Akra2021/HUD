package com.hud.extension

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.regex.Pattern

object NavNotificationParser {


    private val NAV_PACKAGE_HINTS = listOf(
        "ru.yandex.yandexnavi",
        "ru.yandex.navi",
        "yandexnavi",
        "yandex.navi",
        "com.huawei.maps.car.app",
        "com.huawei.maps.app",
        "huawei.maps"
    )

    private val PLACEHOLDER_REGEXES = listOf(
        Regex("""^(яндекс\s*)?навигатор\s+(работает|запущен)\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^(yandex\s*)?navigator\s+is\s+running\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^navigation\s+is\s+running\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^навигация\s+запущена\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^navigating[….\.…]{0,3}$""", RegexOption.IGNORE_CASE),
        Regex("""^в\s+пути\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^服务中\.?$"""),
        Regex("""^petal\s*maps\.?$""", RegexOption.IGNORE_CASE)
    )

    private val DISTANCE_PATTERN = Pattern.compile(
        """(\d+[.,]?\d*)\s*(км|km|м|m)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val TIME_MIN_PATTERN = Pattern.compile(
        """(\d+)\s*(мин|min|минут|minutes|м\.)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val TIME_HOUR_PATTERN = Pattern.compile(
        """(\d+)\s*(ч|h|hr|час)""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val ETA_CLOCK_PATTERN = Pattern.compile(
        """\b(\d{1,2}:\d{2})\b"""
    )
    private val REMAINING_HINT_PATTERN = Pattern.compile(
        """(осталось|remaining|left|до\s+конца|to\s+destination)""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val EXTRA_TEXT_BLOCKLIST = setOf(
        "floating_window_notification",
        "phone",
        "true",
        "false"
    )

    private val EXTRA_KEY_BLOCKLIST = setOf(
        "specialType",
        "remindDevice",
        "externalChannelType",
        "reminderType",
        "PopupBackgroundWindowPrevilege",
        "topFullscreen",
        "isRequestSingleLine",
        "gameDndOn",
        "notification_should_ringtone",
        "hw_enable_small_icon",
        "res_id",
        "notification_index"
    )

    fun isNavPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return NAV_PACKAGE_HINTS.any { lower.contains(it) }
    }

    fun pickBest(
        notifications: Array<StatusBarNotification>,
        context: android.content.Context,
        iconLoader: (Icon) -> Bitmap?
    ): NavGuidance? {
        if (!HudPreferences.isHudEnabled(context)) return null
        val selected = HudPreferences.getSelectedNavPackage(context) ?: return null

        val candidates = notifications
            .filter { isNavPackage(it.packageName) }
            .filter { HudPreferences.matchesSelectedPackage(it.packageName, selected) }

        if (candidates.isEmpty()) {
            HudLog.i("pickBest: no notifications for selected=$selected")
            return null
        }

        val parsed = candidates.map { sbn ->
            logNotificationSnapshot(sbn)
            sbn to parse(sbn, context, iconLoader)
        }

        val best = parsed
            .mapNotNull { (sbn, guidance) -> guidance?.let { sbn to it } }
            .filter { (_, g) -> !g.isPlaceholder && g.hasDisplayableContent() }
            .maxByOrNull { (sbn, g) -> g.detailScore + notificationPriorityBonus(sbn) }
            ?.second

        if (best == null) {
            val placeholders = parsed.count { (_, g) -> g?.isPlaceholder == true }
            HudLog.i("pickBest: ${candidates.size} nav notif(s), parsed=${parsed.size}, placeholders=$placeholders, no displayable content")
        }
        return best
    }

    /** Prefer navigation-channel notifications with route data in title/text. */
    private fun notificationPriorityBonus(sbn: StatusBarNotification): Int {
        var bonus = 0
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (DISTANCE_PATTERN.matcher(title).find()) bonus += 80
        if (containsManeuverHint(text) || containsManeuverHint(title)) bonus += 60
        if (sbn.notification.bigContentView != null) bonus += 100
        if (sbn.notification.contentView != null) bonus += 40
        if (isPlaceholderMessage("$title | $text")) bonus -= 120
        return bonus
    }

    private fun logNotificationSnapshot(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val floating = FloatingWindowNotificationDecoder.isFloatingWindow(extras)
        val topFs = FloatingWindowNotificationDecoder.isTopFullscreen(extras)
        val n = sbn.notification
        HudLog.i(
            "notif id=${sbn.id} pkg=${sbn.packageName} floating=$floating topFullscreen=$topFs " +
                "title='$title' text='$text' " +
                "views=content:${n.contentView != null} big:${n.bigContentView != null} heads:${n.headsUpContentView != null}"
        )
        if (floating && n.bigContentView == null && n.contentView == null) {
            FloatingWindowNotificationDecoder.logExtrasSummary(extras, "notif id=${sbn.id} extras")
        }
        extras.getString("res_id")?.takeIf { it.isNotBlank() }?.let { resId ->
            HudLog.i("notif id=${sbn.id} res_id=${resId.take(200)}")
        }
    }

    private val A11Y_LINE_BLOCKLIST = setOf(
        "map",
        "select route",
        "close",
        "закрыть",
        "navigator is running",
        "yandex navigator",
        "yandex navi",
        "huawei id profile picture"
    )

    private val HUD_JUNK_REGEXES = listOf(
        Regex("""huawei\s*id""", RegexOption.IGNORE_CASE),
        Regex("""profile\s*picture""", RegexOption.IGNORE_CASE),
        Regex("""\bavatar\b""", RegexOption.IGNORE_CASE),
        Regex("""\baccount\s*(photo|picture|avatar)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\buser\s*photo\b""", RegexOption.IGNORE_CASE)
    )

    /** Screen text (Accessibility) when notification has no route payload. */
    fun parseFromTextLines(
        lines: List<String>,
        context: android.content.Context,
        sourcePackage: String
    ): NavGuidance? {
        if (!HudPreferences.isHudEnabled(context)) return null
        val selected = HudPreferences.getSelectedNavPackage(context) ?: return null
        if (!HudPreferences.matchesSelectedPackage(sourcePackage, selected)) return null

        val deduped = FloatingWindowNotificationDecoder.dedupeTexts(
            lines.map { stripA11yLabelPrefix(it) }.filter { !isA11yChromeLine(it) && !isJunkHudLine(it) }
        )
        if (deduped.isEmpty()) return null

        val title = deduped.firstOrNull().orEmpty()
        val text = deduped.getOrNull(1).orEmpty()
        val combined = deduped.joinToString(" | ")
        // In fullscreen the UI can contain arbitrary texts (e.g. account / profile).
        // Only accept data that looks like navigation (distance/time/turn hints).
        if (!hasNavSignals(title, text, deduped)) return null

        return buildGuidanceFromTexts(
            context = context,
            title = title,
            text = text,
            subText = "",
            bigText = "",
            extraLines = emptyList(),
            allTexts = deduped,
            decoded = FloatingWindowNotificationDecoder.Decoded.EMPTY,
            combined = combined,
            logTag = "a11y:$sourcePackage",
            isFloating = false,
            iconLoader = { null }
        )
    }

    fun parse(
        sbn: StatusBarNotification,
        context: android.content.Context,
        iconLoader: (Icon) -> Bitmap?
    ): NavGuidance? {
        if (!isNavPackage(sbn.packageName)) return null

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val isFloating = FloatingWindowNotificationDecoder.isFloatingWindow(extras)
        val decoded = if (isFloating) {
            FloatingWindowNotificationDecoder.decode(context, notification, iconLoader)
        } else {
            FloatingWindowNotificationDecoder.Decoded.EMPTY
        }

        val texts = extractAllTexts(extras)
        if (texts.isEmpty() && decoded.texts.isEmpty() && title.isEmpty() && text.isEmpty()) {
            HudLog.i("parse skip id=${sbn.id}: no text in extras or RemoteViews")
            return null
        }

        val combined = (decoded.texts + texts + listOf(title, text)).joinToString(" | ")
        val topFullscreen = FloatingWindowNotificationDecoder.isTopFullscreen(extras)
        if (!hasNavSignals(title, text, decoded.texts) && isPlaceholderMessage(combined)) {
            HudLog.i("parse placeholder id=${sbn.id}: $combined")
            val noViews = notification.bigContentView == null && notification.contentView == null
            if (sbn.packageName.contains("yandex") && (topFullscreen || (isFloating && noViews))) {
                NavEventHub.setYandexFullscreenPlaceholder(true)
            }
            return NavGuidance(
                instruction = "",
                detail = "",
                routeSummaryText = null,
                distanceMeters = null,
                timeSeconds = null,
                maneuverIconRes = MapboxManeuverResolver.iconFor(MapboxManeuverResolver.ManeuverType.UNKNOWN),
                maneuverType = MapboxManeuverResolver.ManeuverType.UNKNOWN,
                distanceTimerColor = NavTimerColors.forDistanceMeters(null),
                timeTimerColor = NavTimerColors.forTimeSeconds(null),
                notificationIcon = null,
                isPlaceholder = true,
                detailScore = 0
            )
        }

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString() }
            .orEmpty()

        return buildGuidanceFromTexts(
            context = context,
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            extraLines = lines,
            allTexts = texts + decoded.texts,
            decoded = decoded,
            combined = combined,
            logTag = "notif:${sbn.packageName}#${sbn.id}",
            isFloating = isFloating,
            iconLoader = iconLoader
        ).also {
            if (it != null) NavEventHub.setYandexFullscreenPlaceholder(false)
        }
    }

    private fun buildGuidanceFromTexts(
        context: android.content.Context,
        title: String,
        text: String,
        subText: String,
        bigText: String,
        extraLines: List<String>,
        allTexts: List<String>,
        decoded: FloatingWindowNotificationDecoder.Decoded,
        combined: String,
        logTag: String,
        isFloating: Boolean,
        iconLoader: (Icon) -> Bitmap?
    ): NavGuidance? {
        val rawInstruction = pickInstruction(title, text, bigText, extraLines, allTexts)
        val allDistances = findAllDistances(combined)
        val stepDistance = findStepDistance(combined, allDistances)
        val remainingDistance = findRemainingDistance(combined, allDistances)
        val allTimes = findAllTimes(combined)
        val routeTime = findRouteTime(combined, allTimes)
        val allTextLines = FloatingWindowNotificationDecoder.dedupeTexts(
            allTexts + listOf(title, text, subText, bigText) + extraLines
        ).filter { !isPlaceholderMessage(it) && !isJunkHudLine(it) }

        val maneuver = MapboxManeuverResolver.detectManeuver(*(allTextLines + rawInstruction).toTypedArray())
        val hasManeuverIcon = MapboxManeuverResolver.hasManeuverIcon(maneuver) || decoded.icons.isNotEmpty()
        val filteredLines = allTextLines.filter { line ->
            !(hasManeuverIcon && MapboxManeuverResolver.isGenericManeuverLabel(line))
        }
        val maneuverIcon = MapboxManeuverResolver.iconFor(maneuver)
        val remoteManeuverIcon = pickRemoteManeuverIcon(decoded.icons)
        val street = pickStreet(title, text, subText, bigText, extraLines, filteredLines, rawInstruction, combined)

        val routeRemaining = findRouteRemaining(remainingDistance, allDistances, stepDistance)
        val hideManeuverText = hasManeuverIcon && MapboxManeuverResolver.isGenericManeuverLabel(rawInstruction)
        var display = buildDisplay(
            rawInstruction = rawInstruction,
            maneuver = maneuver,
            stepDistance = stepDistance,
            routeRemaining = routeRemaining,
            routeTime = routeTime,
            street = street,
            floatingLines = filteredLines,
            hideManeuverText = hideManeuverText
        )
        display = applyStandardExtrasFallback(display, title, text, hideManeuverText)
        display = sanitizeDisplayLines(display)

        if (!display.instruction.isNotBlank() && !display.detail.isNotBlank() && display.routeSummaryText.isNullOrBlank()) {
            HudLog.i("parse empty ($logTag) combined='$combined'")
            return null
        }

        val score = detailScore(
            instruction = display.instruction,
            distanceMeters = routeRemaining?.meters ?: remainingDistance?.meters ?: stepDistance?.meters,
            timeSeconds = routeTime?.seconds,
            maneuver = maneuver,
            combinedLength = combined.length,
            isFloating = isFloating,
            decodedTextCount = filteredLines.size,
            hasRemoteIcon = remoteManeuverIcon != null
        ) + if (logTag.startsWith("a11y:")) 80 else 0

        HudLog.i(
            "parsed $logTag floating=$isFloating maneuver=$maneuver " +
                "line1=${display.instruction} line2=${display.detail} line3=${display.routeSummaryText}"
        )

        return NavGuidance(
            instruction = display.instruction,
            detail = display.detail,
            routeSummaryText = display.routeSummaryText,
            distanceMeters = routeRemaining?.meters ?: remainingDistance?.meters ?: stepDistance?.meters,
            timeSeconds = routeTime?.seconds,
            maneuverIconRes = maneuverIcon,
            maneuverType = maneuver,
            distanceTimerColor = NavTimerColors.forDistanceMeters(routeRemaining?.meters ?: remainingDistance?.meters),
            timeTimerColor = NavTimerColors.forTimeSeconds(routeTime?.seconds),
            notificationIcon = null,
            displayLines = display.displayLines,
            remoteManeuverIcon = remoteManeuverIcon,
            isPlaceholder = false,
            detailScore = score
        )
    }

    private fun pickRemoteManeuverIcon(icons: List<Bitmap>): Bitmap? =
        icons.firstOrNull { it.width in 16..160 && it.height in 16..160 }

    private data class DisplayLines(
        val instruction: String,
        val detail: String,
        val routeSummaryText: String?,
        val stepDistanceText: String?,
        val arrivalTimeText: String?,
        val displayLines: List<String>
    )

    private fun findRouteRemaining(
        remaining: Distance?,
        all: List<Distance>,
        step: Distance?
    ): Distance? {
        if (remaining != null) return remaining
        if (all.isEmpty()) return null
        if (all.size >= 2) return all.maxByOrNull { it.meters }
        val only = all.first()
        if (step != null && only.meters == step.meters) return null
        return if (only.meters >= 1000) only else null
    }

    private fun buildDisplay(
        rawInstruction: String,
        maneuver: MapboxManeuverResolver.ManeuverType,
        stepDistance: Distance?,
        routeRemaining: Distance?,
        routeTime: Time?,
        street: String,
        floatingLines: List<String>,
        hideManeuverText: Boolean
    ): DisplayLines {
        val line1 = pickStepDistanceLabel(stepDistance, floatingLines)
        val routeDistLabel = routeRemaining?.label
        val routeTimeLabel = routeTime?.label
        val line2 = pickDetailLine(
            street = street,
            floatingLines = floatingLines,
            rawInstruction = rawInstruction,
            hideManeuverText = hideManeuverText,
            stepLabel = line1,
            routeDistLabel = routeDistLabel,
            routeTimeLabel = routeTimeLabel
        )
        val line3 = formatRouteSummary(routeTime, routeRemaining)

        val displayLines = listOfNotNull(
            line1.takeIf { it.isNotBlank() },
            line2.takeIf { it.isNotBlank() },
            line3.takeIf { it.isNotBlank() }
        )

        return DisplayLines(
            instruction = line1,
            detail = line2,
            routeSummaryText = line3,
            stepDistanceText = line1,
            arrivalTimeText = line3,
            displayLines = displayLines
        )
    }

    private fun pickStepDistanceLabel(stepDistance: Distance?, floatingLines: List<String>): String {
        if (stepDistance != null) return stepDistance.label
        return floatingLines
            .filter { isDistanceLine(it) }
            .minByOrNull { findAllDistances(it).firstOrNull()?.meters ?: Int.MAX_VALUE }
            .orEmpty()
    }

    private fun pickDetailLine(
        street: String,
        floatingLines: List<String>,
        rawInstruction: String,
        hideManeuverText: Boolean,
        stepLabel: String,
        routeDistLabel: String?,
        routeTimeLabel: String?
    ): String {
        val excluded = buildSet {
            add(FloatingWindowNotificationDecoder.normalizeKey(stepLabel))
            routeDistLabel?.let { add(FloatingWindowNotificationDecoder.normalizeKey(it)) }
            routeTimeLabel?.let { add(FloatingWindowNotificationDecoder.normalizeKey(it)) }
        }

        fun isExcluded(line: String): Boolean =
            FloatingWindowNotificationDecoder.normalizeKey(line) in excluded

        fun isDetailCandidate(line: String): Boolean {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || isExcluded(trimmed)) return false
            if (isDistanceLine(trimmed) || isTimeOnlyLine(trimmed)) return false
            if (isJunkHudLine(trimmed) || isPlaceholderMessage(trimmed)) return false
            if (hideManeuverText && MapboxManeuverResolver.isGenericManeuverLabel(trimmed)) return false
            return true
        }

        val candidates = floatingLines.filter(::isDetailCandidate)

        candidates.firstOrNull { isCameraLine(it) }?.let { return it.trim() }
        candidates.firstOrNull { isExitLine(it) }?.let { return it.trim() }

        if (street.isNotBlank() && isDetailCandidate(street)) return street.trim()
        candidates.firstOrNull { isStreetLike(it) }?.let { return it.trim() }

        if (!hideManeuverText) {
            if (rawInstruction.isNotBlank() && isDetailCandidate(rawInstruction)) {
                return rawInstruction.trim()
            }
            candidates.firstOrNull {
                containsManeuverHint(it) || MapboxManeuverResolver.isGenericManeuverLabel(it)
            }?.let { return it.trim() }
        }

        return candidates.maxByOrNull { detailLinePriority(it) }.orEmpty().trim()
    }

    private fun isCameraLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("camera") || lower.contains("камер") ||
            lower.contains("radar") || lower.contains("радар") ||
            lower.contains("speed trap") || lower.contains("скорост")
    }

    private fun isExitLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("exit") || lower.contains("съезд") ||
            lower.contains("выезд") || lower.contains("off-ramp") || lower.contains("off ramp")
    }

    private fun isStreetLike(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("ул") || lower.contains("street") || lower.contains(" st.") ||
            lower.contains("просп") || lower.contains("пер.") || lower.contains("шоссе") ||
            lower.contains(" avenue") || lower.contains(" road") || lower.contains(" бульвар")
    }

    private fun detailLinePriority(line: String): Int {
        var score = line.length
        if (isStreetLike(line)) score += 30
        if (isExitLine(line)) score += 40
        if (isCameraLine(line)) score += 50
        if (containsManeuverHint(line)) score += 20
        return score
    }

    private fun isDistanceLine(line: String): Boolean =
        DISTANCE_PATTERN.matcher(line.trim()).matches()

    private fun isTimeOnlyLine(line: String): Boolean {
        val trimmed = line.trim()
        return TIME_MIN_PATTERN.matcher(trimmed).matches() ||
            TIME_HOUR_PATTERN.matcher(trimmed).matches() ||
            ETA_CLOCK_PATTERN.matcher(trimmed).matches()
    }

    private fun formatRouteSummary(
        routeTime: Time?,
        routeRemaining: Distance?
    ): String {
        val timePart = routeTime?.label?.takeIf { it.isNotBlank() }
        val distPart = routeRemaining?.label?.takeIf { it.isNotBlank() }
        return when {
            timePart != null && distPart != null -> "$timePart | $distPart"
            timePart != null -> timePart
            distPart != null -> distPart
            else -> ""
        }
    }

    private fun pickStreet(
        title: String,
        text: String,
        subText: String,
        bigText: String,
        lines: List<String>,
        allTexts: List<String>,
        rawInstruction: String,
        combined: String
    ): String {
        val candidates = linkedSetOf<String>()
        listOf(subText, title, text, bigText).forEach { addStreetCandidate(candidates, it) }
        lines.forEach { addStreetCandidate(candidates, it) }
        allTexts.forEach { addStreetCandidate(candidates, it) }

        return candidates
            .filter { it != rawInstruction }
            .filter { !MapboxManeuverResolver.isGenericManeuverLabel(it) }
            .filter { !isPlaceholderMessage(it) }
            .filter { !DISTANCE_PATTERN.matcher(it).matches() }
            .filter { !ETA_CLOCK_PATTERN.matcher(it).matches() }
            .filter { !containsOnlyMeta(it) }
            .filter { !isJunkHudLine(it) }
            .maxByOrNull { streetPriority(it) }
            .orEmpty()
    }

    private fun addStreetCandidate(out: LinkedHashSet<String>, value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isNotEmpty()) out.add(v)
    }

    private fun containsOnlyMeta(line: String): Boolean {
        val t = line.lowercase()
        return t.startsWith("осталось") || t.contains("remaining") ||
            t.contains("navigator is running") || t.contains("прибытие")
    }

    private fun streetPriority(line: String): Int {
        var score = line.length
        val lower = line.lowercase()
        if (line.contains("ул", ignoreCase = true) || line.contains("street", ignoreCase = true)) score += 20
        if (lower.contains("exit") || lower.contains("съезд") || lower.contains("выезд")) score += 25
        if (lower.contains("camera") || lower.contains("камер") || lower.contains("radar") || lower.contains("радар")) score += 25
        if (MapboxManeuverResolver.isGenericManeuverLabel(line)) score -= 100
        if (DISTANCE_PATTERN.matcher(line).find()) score -= 50
        return score
    }

    private fun findAllDistances(text: String): List<Distance> {
        val matcher = DISTANCE_PATTERN.matcher(text)
        val list = mutableListOf<Distance>()
        while (matcher.find()) {
            val value = matcher.group(1)?.replace(',', '.')?.toDoubleOrNull() ?: continue
            val unit = matcher.group(2)?.lowercase().orEmpty()
            val meters = if (unit == "км" || unit == "km") (value * 1000).toInt() else value.toInt()
            list.add(Distance(meters, matcher.group().trim()))
        }
        return list
    }

    private fun findStepDistance(text: String, all: List<Distance>): Distance? {
        if (all.isEmpty()) return null
        if (all.size == 1) return all.first()
        val withHint = all.filter { lineHasStepHint(text, it.label) }
        return withHint.minByOrNull { it.meters } ?: all.minByOrNull { it.meters }
    }

    private fun findRemainingDistance(text: String, all: List<Distance>): Distance? {
        if (all.isEmpty()) return null
        val withHint = all.filter { lineHasRemainingHint(text, it.label) }
        if (withHint.isNotEmpty()) return withHint.maxByOrNull { it.meters }
        if (all.size >= 2) return all.maxByOrNull { it.meters }
        val only = all.first()
        return if (only.meters >= 1000) only else null
    }

    private fun lineHasStepHint(fullText: String, label: String): Boolean {
        val idx = fullText.indexOf(label)
        if (idx < 0) return false
        val window = fullText.substring(maxOf(0, idx - 30), minOf(fullText.length, idx + label.length + 30)).lowercase()
        return window.contains("через") || window.contains("in ") || window.contains("then") ||
            window.contains("через ") || !window.contains("остал")
    }

    private fun lineHasRemainingHint(fullText: String, label: String): Boolean {
        val idx = fullText.indexOf(label)
        if (idx < 0) return false
        val window = fullText.substring(maxOf(0, idx - 40), minOf(fullText.length, idx + label.length + 40)).lowercase()
        return REMAINING_HINT_PATTERN.matcher(window).find()
    }

    private fun isA11yChromeLine(line: String): Boolean {
        val norm = line.trim().lowercase().trimEnd('.', '!', '?')
        if (norm.isEmpty()) return true
        return norm in A11Y_LINE_BLOCKLIST || isJunkHudLine(line)
    }

    private fun isJunkHudLine(line: String): Boolean {
        if (HudOverlayIcons.isFinishRouteMessage(line)) return true
        val norm = line.trim().lowercase().trimEnd('.', '!', '?')
        if (norm.isEmpty()) return true
        if (HUD_JUNK_REGEXES.any { it.containsMatchIn(norm) }) return true
        if (norm.contains("profile") && norm.contains("picture")) return true
        if (norm.contains("huawei") && (norm.contains("id") || norm.contains("account"))) return true
        return false
    }

    private fun sanitizeDisplayLines(display: DisplayLines): DisplayLines {
        var line1 = HudOverlayIcons.sanitizeLine(display.instruction.takeUnless { isJunkHudLine(it) }.orEmpty())
        var line2 = HudOverlayIcons.sanitizeLine(display.detail.takeUnless { isJunkHudLine(it) }.orEmpty())
        var line3 = display.routeSummaryText?.let { HudOverlayIcons.sanitizeLine(it) }?.takeIf { it.isNotBlank() }

        // Line 1: step distance only.
        if (line1.isNotBlank() && !isDistanceLine(line1)) {
            if (line2.isBlank() && isDetailLineCandidate(line1, hideManeuverText = false)) {
                line2 = line1
            }
            line1 = ""
        }
        if (line1.isBlank()) {
            line1 = display.stepDistanceText.orEmpty().takeIf { isDistanceLine(it) }.orEmpty()
        }

        // Line 2: street / camera / exit / maneuver — never time or distance.
        if (line2.isNotBlank() && (isDistanceLine(line2) || isTimeOnlyLine(line2))) {
            line2 = ""
        }

        // Line 3: route time | route distance.
        if (!line3.isNullOrBlank() && isDistanceLine(line3) && !line3.contains("|")) {
            line3 = null
        }

        val displayLines = listOfNotNull(
            line1.takeIf { it.isNotBlank() },
            line2.takeIf { it.isNotBlank() },
            line3?.takeIf { it.isNotBlank() }
        )
        return display.copy(
            instruction = line1,
            detail = line2,
            routeSummaryText = line3,
            stepDistanceText = line1,
            arrivalTimeText = line3,
            displayLines = displayLines
        )
    }

    private fun stripA11yLabelPrefix(line: String): String {
        val trimmed = line.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("arrival time ") -> trimmed.substringAfter(' ', "").trim()
            lower.startsWith("time remaining ") -> trimmed.substringAfter(' ', "").trim()
            else -> trimmed
        }
    }

    private fun hasNavSignals(title: String, text: String, decodedTexts: List<String>): Boolean {
        val lines = listOf(title, text) + decodedTexts
        return lines.any { line ->
            val t = line.trim()
            t.isNotEmpty() && (
                DISTANCE_PATTERN.matcher(t).find() ||
                    TIME_MIN_PATTERN.matcher(t).find() ||
                    TIME_HOUR_PATTERN.matcher(t).find() ||
                    ETA_CLOCK_PATTERN.matcher(t).find() ||
                    containsManeuverHint(t) ||
                    DIST_TO_GO_PATTERN.matcher(t).find() ||
                    REMAINING_HINT_PATTERN.matcher(t).find()
                )
        }
    }

    private val DIST_TO_GO_PATTERN = Pattern.compile(
        """(dist\.?\s*to\s*go|до\s+(конца|цели|места)|осталось)""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private fun applyStandardExtrasFallback(
        display: DisplayLines,
        title: String,
        text: String,
        hideManeuverText: Boolean
    ): DisplayLines {
        var line1 = display.instruction
        var line2 = display.detail
        var line3 = display.routeSummaryText

        if (line1.isBlank() && isDistanceLine(title)) line1 = title.trim()
        if (line2.isBlank() && text.isNotBlank() && isDetailLineCandidate(text, hideManeuverText)) {
            line2 = text.trim()
        }

        val displayLines = listOfNotNull(
            line1.takeIf { it.isNotBlank() },
            line2.takeIf { it.isNotBlank() },
            line3?.takeIf { it.isNotBlank() }
        )
        return display.copy(
            instruction = line1,
            detail = line2,
            routeSummaryText = line3,
            stepDistanceText = line1,
            arrivalTimeText = line3,
            displayLines = displayLines
        )
    }

    private fun isPlaceholderMessage(combined: String): Boolean {
        val normalized = combined.trim()
        if (normalized.isEmpty()) return true
        if (hasNavSignals("", "", normalized.split("|").map { it.trim() })) return false
        PLACEHOLDER_REGEXES.forEach { if (it.matches(normalized)) return true }
        if (normalized.contains("navigator is running", ignoreCase = true) &&
            !DISTANCE_PATTERN.matcher(normalized).find() &&
            !containsManeuverHint(normalized)
        ) {
            return true
        }
        if (normalized.contains("навигатор", ignoreCase = true) &&
            normalized.contains("работает", ignoreCase = true) &&
            !DISTANCE_PATTERN.matcher(normalized).find()
        ) {
            return true
        }
        if (normalized.contains("navigating", ignoreCase = true) &&
            !DISTANCE_PATTERN.matcher(normalized).find() &&
            !containsManeuverHint(normalized)
        ) {
            return true
        }
        if (normalized.contains("服务中") && !DISTANCE_PATTERN.matcher(normalized).find()) {
            return true
        }
        return false
    }

    private fun containsManeuverHint(text: String): Boolean {
        val hints = listOf(
            "поверн", "через", "м ", "km", "км", "turn", "exit", "merge", "roundabout", "разворот",
            "go right", "go left", "go straight", "направо", "налево", "bear", "keep"
        )
        return hints.any { text.contains(it, ignoreCase = true) }
    }

    private val ANDROID_TEXT_KEYS = setOf(
        Notification.EXTRA_TITLE,
        Notification.EXTRA_TEXT,
        Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_INFO_TEXT,
        Notification.EXTRA_SUMMARY_TEXT
    )

    private fun extractAllTexts(extras: Bundle): List<String> {
        val out = linkedSetOf<String>()
        for (key in extras.keySet()) {
            if (key in EXTRA_KEY_BLOCKLIST) continue
            if (key.startsWith("android.") && key !in ANDROID_TEXT_KEYS) continue
            when (val value = extras.get(key)) {
                null -> Unit
                is CharSequence -> addExtraText(out, value.toString())
                is String -> addExtraText(out, value)
                is Array<*> -> value.filterIsInstance<CharSequence>().forEach { addExtraText(out, it.toString()) }
                is ArrayList<*> -> value.forEach { item ->
                    when (item) {
                        is CharSequence -> addExtraText(out, item.toString())
                        is String -> addExtraText(out, item)
                    }
                }
            }
        }
        FloatingWindowNotificationDecoder.decodeExtrasDeep(extras, out)
        return out.filter { it.isNotBlank() }
    }

    private fun addExtraText(out: LinkedHashSet<String>, raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        if (value.lowercase() in EXTRA_TEXT_BLOCKLIST) return
        out.add(value)
    }

    private fun pickInstruction(
        title: String,
        text: String,
        bigText: String,
        lines: List<String>,
        allTexts: List<String>
    ): String {
        val candidates = listOf(text, bigText, lines.firstOrNull().orEmpty(), title) +
            allTexts.filter { containsManeuverHint(it) || DISTANCE_PATTERN.matcher(it).find() }

        return candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isPlaceholderMessage(it) }
            .maxByOrNull { instructionPriority(it) }
            .orEmpty()
    }


    private fun instructionPriority(line: String): Int {
        var score = line.length
        if (DISTANCE_PATTERN.matcher(line).find()) score += 40
        if (TIME_MIN_PATTERN.matcher(line).find() || TIME_HOUR_PATTERN.matcher(line).find()) score += 30
        if (containsManeuverHint(line)) score += 50
        if (isPlaceholderMessage(line)) score -= 200
        return score
    }

    private data class Distance(val meters: Int, val label: String)
    private data class Time(val seconds: Int, val label: String)

    private fun isDetailLineCandidate(line: String, hideManeuverText: Boolean): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (isPlaceholderMessage(trimmed) || isJunkHudLine(trimmed)) return false
        if (isDistanceLine(trimmed) || isTimeOnlyLine(trimmed)) return false
        if (hideManeuverText && MapboxManeuverResolver.isGenericManeuverLabel(trimmed)) return false
        return true
    }

    private fun findAllTimes(text: String): List<Time> {
        val list = mutableListOf<Time>()

        val minMatcher = TIME_MIN_PATTERN.matcher(text)
        while (minMatcher.find()) {
            val min = minMatcher.group(1)?.toIntOrNull() ?: continue
            list.add(Time(min * 60, minMatcher.group().trim()))
        }

        val hourMatcher = TIME_HOUR_PATTERN.matcher(text)
        while (hourMatcher.find()) {
            val h = hourMatcher.group(1)?.toIntOrNull() ?: continue
            list.add(Time(h * 3600, hourMatcher.group().trim()))
        }

        return list
    }

    private fun findRouteTime(text: String, all: List<Time>): Time? {
        if (all.isEmpty()) return null
        if (all.size == 1) return all.first()
        val withHint = all.filter { lineHasRemainingHint(text, it.label) }
        return withHint.maxByOrNull { it.seconds } ?: all.maxByOrNull { it.seconds }
    }

    private fun findTime(text: String): Time? = findRouteTime(text, findAllTimes(text))

    private fun detailScore(
        instruction: String,
        distanceMeters: Int?,
        timeSeconds: Int?,
        maneuver: MapboxManeuverResolver.ManeuverType,
        combinedLength: Int,
        isFloating: Boolean = false,
        decodedTextCount: Int = 0,
        hasRemoteIcon: Boolean = false
    ): Int {
        var score = combinedLength
        if (instruction.isNotBlank()) score += 30
        if (distanceMeters != null) score += 40
        if (timeSeconds != null) score += 35
        if (maneuver != MapboxManeuverResolver.ManeuverType.UNKNOWN) score += 45
        if (isFloating) score += 120
        score += decodedTextCount * 8
        if (hasRemoteIcon) score += 60
        return score
    }

    fun iconToBitmap(icon: Icon, context: android.content.Context): Bitmap? {
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            if (drawable is android.graphics.drawable.BitmapDrawable) return drawable.bitmap

            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
