package com.hud.extension

import android.content.Context

object HudPreferences {

    private const val PREFS = "hud_prefs"
    private const val KEY_SELECTED_NAV_PACKAGE = "selected_nav_package"

    val knownNavPackages = listOf(
        "ru.yandex.yandexnavi",
        "com.huawei.maps.car.app",
        "com.huawei.maps.app"
    )

    const val PETAL_PACKAGE_PRIMARY = "com.huawei.maps.app"
    const val PETAL_PACKAGE_CAR = "com.huawei.maps.car.app"

    val petalPackages = setOf(PETAL_PACKAGE_PRIMARY, PETAL_PACKAGE_CAR)

    fun isHudEnabled(context: Context): Boolean =
        NotificationAccessHelper.isUserListeningEnabled(context)

    fun getSelectedNavPackage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_NAV_PACKAGE, null)
    }

    fun setSelectedNavPackage(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_NAV_PACKAGE, packageName)
            .apply()
        HudLog.i("selected nav package: ${packageName ?: "none"}")
    }

    fun ensureDefaultSelection(context: Context) {
        val current = getSelectedNavPackage(context)
        if (current != null) {
            val normalized = normalizeNavPackage(context, current)
            if (normalized != null && normalized != current) {
                setSelectedNavPackage(context, normalized)
            }
            return
        }
        val pm = context.packageManager
        val defaultPkg = knownNavPackages.firstOrNull { pkg ->
            runCatching {
                pm.getApplicationInfo(pkg, 0)
                true
            }.getOrElse { false }
        } ?: knownNavPackages.first()
        setSelectedNavPackage(context, normalizeNavPackage(context, defaultPkg) ?: defaultPkg)
    }

    fun matchesSelectedPackage(notificationPackage: String, selectedPackage: String): Boolean {
        if (notificationPackage == selectedPackage) return true
        if (selectedPackage.contains("yandexnavi") && notificationPackage.contains("yandexnavi")) return true
        if (selectedPackage.contains("huawei.maps") && notificationPackage.contains("huawei.maps")) return true
        if (selectedPackage.contains("maps.car.app") && notificationPackage.contains("maps.car.app")) return true
        return false
    }

    fun isSelectedNavPackage(context: Context, packageName: String): Boolean {
        val selected = getSelectedNavPackage(context) ?: return false
        return matchesSelectedPackage(packageName, selected)
    }

    fun isPetalPackage(packageName: String): Boolean =
        packageName in petalPackages

    fun normalizeNavPackage(context: Context, packageName: String?): String? {
        if (packageName == null) return null
        return if (isPetalPackage(packageName)) resolvePetalPackage(context) else packageName
    }

    fun isSameNavSource(selected: String?, appPackage: String): Boolean {
        if (selected == null) return false
        if (selected == appPackage) return true
        return isPetalPackage(selected) && isPetalPackage(appPackage)
    }

    fun isAnyPetalInstalled(context: Context): Boolean =
        petalPackages.any { isPackageInstalled(context, it) }

    fun resolvePetalPackage(context: Context): String {
        if (isPackageInstalled(context, PETAL_PACKAGE_PRIMARY)) return PETAL_PACKAGE_PRIMARY
        if (isPackageInstalled(context, PETAL_PACKAGE_CAR)) return PETAL_PACKAGE_CAR
        return PETAL_PACKAGE_PRIMARY
    }

    fun isPetalRunning(runningPackages: Collection<String>): Boolean =
        runningPackages.any { pkg ->
            isPetalPackage(pkg) || pkg.contains("huawei.maps")
        }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrElse { false }

    fun needsAccessibilityForSelected(context: Context): Boolean {
        if (!AccessibilityHelper.isFeatureAvailable(context)) return false
        val selected = getSelectedNavPackage(context) ?: return false
        return selected.contains("yandex", ignoreCase = true)
    }
}
