package com.hud.extension

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Yandex Navigator on Harmony fullscreen does not expose route data in notifications.
 * Read maneuver / distance / ETA from on-screen UI via Accessibility.
 */
class NavAccessibilityService : AccessibilityService() {

    private var lastScanAt = 0L
    private var lastPollScanAt = 0L

    override fun onCreate() {
        super.onCreate()
        HudLog.i("a11y service onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        HudLog.i("a11y service connected")
        scanAllNavWindows("connected", force = true)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !HudPreferences.isHudEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (!isRelevantPackage(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastScanAt < MIN_EVENT_INTERVAL_MS) return
        lastScanAt = now
        scanAllNavWindows("event:$pkg", force = false)
    }

    override fun onInterrupt() = Unit

    fun scanAllNavWindows(reason: String, force: Boolean) {
        if (!HudPreferences.isHudEnabled(this)) return

        val isPoll = reason.startsWith("poll")
        val now = System.currentTimeMillis()
        if (isPoll) {
            if (!force && now - lastPollScanAt < POLL_MIN_INTERVAL_MS) return
            lastPollScanAt = now
        } else if (!force && now - lastScanAt < MIN_EVENT_INTERVAL_MS) {
            return
        }

        val selected = HudPreferences.getSelectedNavPackage(this)
        if (selected == null) {
            HudLog.i("a11y scan ($reason): no selected nav app")
            return
        }

        val textsByPackage = linkedMapOf<String, MutableList<String>>()
        var windowCount = 0

        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            try {
                windowCount++
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isEmpty() || !isRelevantPackage(pkg)) continue
                val bucket = textsByPackage.getOrPut(pkg) { mutableListOf() }
                collectTexts(root, bucket, depth = 0)
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }

        if (textsByPackage.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    windowCount++
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg.isNotEmpty() && isRelevantPackage(pkg)) {
                        val bucket = textsByPackage.getOrPut(pkg) { mutableListOf() }
                        collectTexts(root, bucket, depth = 0)
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        }

        var best: NavGuidance? = null
        var bestPkg = ""

        for ((pkg, texts) in textsByPackage) {
            if (texts.isEmpty()) continue
            val guidance = NavNotificationParser.parseFromTextLines(texts, this, pkg) ?: continue
            if (best == null || guidance.detailScore > best!!.detailScore) {
                best = guidance
                bestPkg = pkg
            }
        }

        if (best == null) {
            val sample = textsByPackage.entries
                .flatMap { (pkg, lines) -> lines.take(3).map { "$pkg:'$it'" } }
                .take(6)
                .joinToString(", ")
            HudLog.i("a11y scan ($reason): no guidance ($windowCount windows) sample=[$sample]")
            return
        }

        HudLog.i(
            "a11y publish ($reason) pkg=$bestPkg windows=$windowCount: " +
                "'${best.instruction}' / '${best.detail}' / '${best.routeSummaryText}'"
        )
        NavEventHub.publishNav(best, this)
    }

    private fun isRelevantPackage(pkg: String): Boolean {
        val selected = HudPreferences.getSelectedNavPackage(this) ?: return false
        return HudPreferences.matchesSelectedPackage(pkg, selected)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_TEXT_NODES) return
        // Only visible text — contentDescription carries avatar labels ("Huawei ID profile picture").
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, depth + 1)
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }

    companion object {
        private const val MIN_EVENT_INTERVAL_MS = 300L
        private const val POLL_MIN_INTERVAL_MS = 1_200L
        private const val MAX_DEPTH = 20
        private const val MAX_TEXT_NODES = 200

        @Volatile
        private var instance: NavAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun requestScan(context: Context, reason: String = "request") {
            val service = instance
            if (service != null) {
                service.scanAllNavWindows(reason, force = reason.startsWith("poll") || reason.contains("notifEmpty"))
                return
            }
            if (AccessibilityHelper.isEnabledInSettings(context)) {
                HudLog.w("a11y requestScan ($reason): enabled in settings but service not bound — toggle off/on or restart app")
                AccessibilityHelper.logDiagnostics(context)
            } else {
                HudLog.i("a11y requestScan ($reason): not enabled in settings")
                AccessibilityHelper.logDiagnostics(context)
            }
        }

        @Deprecated("Use requestScan(context, reason)", ReplaceWith("requestScan(context, reason)"))
        fun requestScan(reason: String) {
            instance?.scanAllNavWindows(reason, force = reason.startsWith("poll") || reason.contains("notifEmpty"))
                ?: HudLog.i("a11y requestScan ($reason): service not running (no context)")
        }
    }
}
