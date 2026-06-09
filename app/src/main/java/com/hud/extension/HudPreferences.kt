package com.hud.extension

import android.content.Context

object HudPreferences {

    private const val PREFS = "hud_prefs"
    private const val KEY_SELECTED_NAV_PACKAGE = "selected_nav_package"

    const val YANDEX_PACKAGE = "ru.yandex.yandexnavi"
    const val DGIS_PACKAGE = "ru.dublgis.dgismobile"
    const val DGIS_PACKAGE_LEGACY = "ru.dublgis.dgis"

    val dgisPackages = setOf(DGIS_PACKAGE, DGIS_PACKAGE_LEGACY)
    val knownNavPackages = listOf(YANDEX_PACKAGE, DGIS_PACKAGE)

    fun isHudEnabled(context: Context): Boolean =
        NotificationAccessHelper.isUserListeningEnabled(context)

    fun getSelectedNavPackage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_NAV_PACKAGE, null)
            ?.takeIf { isKnownNavPackage(it) }

    fun setSelectedNavPackage(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_NAV_PACKAGE, packageName)
            .apply()
        HudLog.i("selected nav package: ${packageName ?: "none"}")
    }

    fun ensureDefaultSelection(context: Context) {
        val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_NAV_PACKAGE, null)
        if (current != null && !isKnownNavPackage(current)) {
            setSelectedNavPackage(context, defaultNavPackage(context))
            return
        }
        if (getSelectedNavPackage(context) != null) return
        setSelectedNavPackage(context, defaultNavPackage(context))
    }

    fun matchesSelectedPackage(notificationPackage: String, selectedPackage: String): Boolean {
        if (notificationPackage == selectedPackage) return true
        if (selectedPackage.contains("yandexnavi") && notificationPackage.contains("yandexnavi")) {
            return true
        }
        if (isDgisPackage(selectedPackage) && isDgisPackage(notificationPackage)) return true
        return false
    }

    fun isSelectedNavPackage(context: Context, packageName: String): Boolean {
        val selected = getSelectedNavPackage(context) ?: return false
        return matchesSelectedPackage(packageName, selected)
    }

    fun isDgisPackage(packageName: String?): Boolean =
        packageName != null && (packageName in dgisPackages || packageName.contains("dublgis"))

    fun isDgisSelected(context: Context): Boolean =
        isDgisPackage(getSelectedNavPackage(context))

    fun isYandexPackage(packageName: String?): Boolean =
        packageName != null && packageName.contains("yandexnavi")

    fun isYandexSelected(context: Context): Boolean =
        isYandexPackage(getSelectedNavPackage(context))

    fun usesNotificationNavPath(context: Context): Boolean =
        isHudEnabled(context)

    fun isDgisInstalled(context: Context): Boolean =
        isPackageInstalled(context, DGIS_PACKAGE) ||
            isPackageInstalled(context, DGIS_PACKAGE_LEGACY)

    fun isSameNavSource(selected: String?, appPackage: String): Boolean {
        if (selected == null) return false
        if (selected == appPackage) return true
        if (isDgisPackage(selected) && isDgisPackage(appPackage)) return true
        return false
    }

    fun resolveDgisPackage(context: Context): String =
        when {
            isPackageInstalled(context, DGIS_PACKAGE) -> DGIS_PACKAGE
            isPackageInstalled(context, DGIS_PACKAGE_LEGACY) -> DGIS_PACKAGE_LEGACY
            else -> DGIS_PACKAGE
        }

    fun needsAccessibilityForSelected(context: Context): Boolean {
        if (!AccessibilityHelper.isFeatureAvailable(context)) return false
        val selected = getSelectedNavPackage(context) ?: return false
        return selected.contains("yandex", ignoreCase = true)
    }

    private fun isKnownNavPackage(packageName: String): Boolean =
        packageName == YANDEX_PACKAGE || isDgisPackage(packageName)

    private fun defaultNavPackage(context: Context): String =
        knownNavPackages.firstOrNull { isPackageInstalled(context, it) } ?: YANDEX_PACKAGE

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrElse { false }
}
