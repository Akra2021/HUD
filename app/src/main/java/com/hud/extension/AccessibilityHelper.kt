package com.hud.extension

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

object AccessibilityHelper {

    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_ACCESSIBILITY_COMPONENT =
        "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME"

    fun component(context: Context): ComponentName =
        ComponentName(context, NavAccessibilityService::class.java)

    fun isFeatureAvailable(context: Context): Boolean {
        return try {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .resolveActivity(context.packageManager) != null
        } catch (_: Exception) {
            false
        }
    }

    fun isEnabled(context: Context): Boolean {
        if (!isFeatureAvailable(context)) return false
        if (NavAccessibilityService.isRunning()) return true
        return isEnabledInSettings(context)
    }

    fun isEnabledInSettings(context: Context): Boolean {
        if (isEnabledViaAccessibilityManager(context)) return true
        return isEnabledViaSecureSettings(context)
    }

    fun isBound(): Boolean = NavAccessibilityService.isRunning()

    /** Enabled in system settings but AccessibilityService not connected yet. */
    fun isEnabledButNotBound(context: Context): Boolean =
        isEnabledInSettings(context) && !NavAccessibilityService.isRunning()

    fun openSettings(context: Context): Boolean {
        val activity = context.findActivity()
        val componentFlat = component(context).flattenToString()

        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intents.add(
                Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                    putExtra(EXTRA_ACCESSIBILITY_COMPONENT, componentFlat)
                }
            )
        }
        intents.add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) == null) continue
                if (activity != null) {
                    activity.startActivity(intent)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                HudLog.i("opened accessibility settings: ${intent.action}")
                return true
            } catch (e: Exception) {
                HudLog.e("openSettings failed for ${intent.action}", e)
            }
        }
        return false
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        try {
            context.findActivity()?.startActivity(intent) ?: run {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            HudLog.e("openAppDetails failed", e)
        }
    }

    fun refreshIfEnabled(context: Context) {
        logDiagnostics(context)
        if (!isEnabledInSettings(context)) return
        NavAccessibilityService.requestScan(context, "a11yRefresh")
    }

    fun logDiagnostics(context: Context) {
        val ours = component(context)
        val uid = Process.myUid()
        val userId = currentUserId()
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val secureRaw = readAllEnabledServicesStrings(context)
        val managerEnabled = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).orEmpty()

        HudLog.i(
            "a11y diag: uid=$uid userId=$userId running=${NavAccessibilityService.isRunning()} " +
                "global=${am?.isEnabled} secureGlobal=${isGlobalAccessibilityEnabled(context)} " +
                "ours=${ours.flattenToString()}"
        )
        HudLog.i("a11y diag: secureServices=${secureRaw.take(300)}")
        if (managerEnabled.isEmpty()) {
            HudLog.i("a11y diag: AccessibilityManager enabled list empty")
        } else {
            managerEnabled.forEach { info ->
                val si = info.resolveInfo?.serviceInfo
                HudLog.i(
                    "a11y diag: manager service ${si?.packageName}/${si?.name}"
                )
            }
        }
        HudLog.i(
            "a11y diag: inSettings=${isEnabledInSettings(context)} " +
                "bound=${NavAccessibilityService.isRunning()}"
        )
    }

    private fun isEnabledViaAccessibilityManager(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!am.isEnabled) return false
        val ours = component(context)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { matchesOurService(ours, it) }
    }

    private fun matchesOurService(ours: ComponentName, info: AccessibilityServiceInfo): Boolean {
        val si = info.resolveInfo?.serviceInfo ?: return false
        if (si.packageName != ours.packageName) return false
        return si.name == ours.className ||
            si.name.endsWith(".NavAccessibilityService") ||
            ComponentName(si.packageName, si.name) == ours
    }

    private fun isEnabledViaSecureSettings(context: Context): Boolean {
        if (!isGlobalAccessibilityEnabled(context)) return false
        val ours = component(context)
        val enabledRaw = readAllEnabledServicesStrings(context)
        if (enabledRaw.isBlank()) return false

        val enabledLower = enabledRaw.lowercase()
        val flat = ours.flattenToString().lowercase()
        val shortFlat = ours.flattenToShortString().lowercase()
        val simple = "${context.packageName}/${NavAccessibilityService::class.java.simpleName}".lowercase()

        if (enabledLower.contains(flat) ||
            enabledLower.contains(shortFlat) ||
            enabledLower.contains(simple)
        ) {
            return true
        }

        return TextUtils.SimpleStringSplitter(':').let { splitter ->
            splitter.setString(enabledRaw)
            splitter.any { entry ->
                val trimmed = entry.trim()
                trimmed.equals(ours.flattenToString(), ignoreCase = true) ||
                    trimmed.equals(ours.flattenToShortString(), ignoreCase = true) ||
                    (trimmed.contains(context.packageName, ignoreCase = true) &&
                        trimmed.contains("NavAccessibilityService", ignoreCase = true))
            }
        }
    }

    private fun isGlobalAccessibilityEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return true
        return readGlobalAccessibilityForAllUsers(cr).any { it == 1 }
    }

    private fun readAllEnabledServicesStrings(context: Context): String {
        val cr = context.contentResolver
        val parts = linkedSetOf<String>()

        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.takeIf { it.isNotBlank() }
            ?.let { parts.add(it) }

        readSecureStringForUser(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.takeIf { it.isNotBlank() }
            ?.let { parts.add(it) }

        // Harmony / multi-user: scan nearby user profiles (e.g. car head unit user 13).
        val myUserId = currentUserId()
        for (userId in (myUserId - 2)..(myUserId + 2)) {
            if (userId < 0) continue
            readSecureStringForUser(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, userId)
                ?.takeIf { it.isNotBlank() }
                ?.let { parts.add(it) }
        }

        return parts.joinToString(":")
    }

    private fun readGlobalAccessibilityForAllUsers(cr: android.content.ContentResolver): List<Int> {
        val values = mutableListOf<Int>()
        val myUserId = currentUserId()
        for (userId in (myUserId - 2)..(myUserId + 2)) {
            if (userId < 0) continue
            readSecureIntForUser(cr, Settings.Secure.ACCESSIBILITY_ENABLED, userId)?.let(values::add)
        }
        return values
    }

    private fun readSecureStringForUser(
        cr: android.content.ContentResolver,
        key: String,
        userId: Int = currentUserId()
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return null
        return try {
            val method = Settings.Secure::class.java.getMethod(
                "getStringForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, cr, key, userId) as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun readSecureIntForUser(
        cr: android.content.ContentResolver,
        key: String,
        userId: Int
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return null
        return try {
            val method = Settings.Secure::class.java.getMethod(
                "getIntForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, cr, key, 0, userId) as? Int
        } catch (e: Exception) {
            null
        }
    }

    private fun currentUserId(): Int = Process.myUid() / 100000

    private fun Context.findActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
