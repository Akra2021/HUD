package com.hud.extension

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Декодирование Huawei/Harmony «floating window» уведомлений (specialType).
 * Яндекс Навигатор кладёт манёвр, дистанции и иконки в RemoteViews (bigContentView).
 */
object FloatingWindowNotificationDecoder {

    const val SPECIAL_TYPE_KEY = "specialType"
    const val SPECIAL_TYPE_VALUE = "floating_window_notification"

    data class Decoded(
        val texts: List<String>,
        val icons: List<Bitmap>
    ) {
        companion object {
            val EMPTY = Decoded(emptyList(), emptyList())
        }
    }

    fun isFloatingWindow(extras: Bundle): Boolean =
        extras.getString(SPECIAL_TYPE_KEY)?.equals(SPECIAL_TYPE_VALUE, ignoreCase = true) == true

    fun isTopFullscreen(extras: Bundle): Boolean =
        extras.getBoolean("topFullscreen", false) ||
            extras.getString("topFullscreen")?.equals("true", ignoreCase = true) == true

    fun decode(
        context: Context,
        notification: Notification,
        iconLoader: (Icon) -> Bitmap?
    ): Decoded {
        val texts = linkedSetOf<String>()
        val icons = mutableListOf<Bitmap>()

        val remoteViewList = listOfNotNull(
            notification.bigContentView,
            notification.contentView,
            notification.headsUpContentView
        ).distinctBy { it.hashCode() }

        if (remoteViewList.isEmpty()) {
            HudLog.i("floating decode: no RemoteViews on notification")
            decodeExtrasDeep(notification.extras, texts)
        } else {
            remoteViewList.forEach { remoteViews ->
                decodeRemoteViews(context, remoteViews, texts, icons)
            }
        }

        notification.actions?.forEach { action ->
            action.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        }

        notification.getLargeIcon()?.let { iconLoader(it)?.let(icons::add) }
        if (icons.isEmpty()) {
            notification.getSmallIcon()?.let { iconLoader(it)?.let(icons::add) }
        }

        val outTexts = dedupeTexts(texts)
        if (outTexts.isNotEmpty() || icons.isNotEmpty()) {
            HudLog.i("floating decode: texts=${outTexts.size} icons=${icons.size} sample='${outTexts.firstOrNull()}'")
        }
        return Decoded(
            texts = outTexts,
            icons = icons.filter { it.width >= 8 && it.height >= 8 }
        )
    }

    /** Walk nested Bundle keys — Harmony may hide route strings without RemoteViews. */
    fun decodeExtrasDeep(extras: Bundle?, out: MutableCollection<String>, depth: Int = 0) {
        if (extras == null || depth > 6) return
        for (key in extras.keySet()) {
            when (val value = extras.get(key)) {
                null -> Unit
                is CharSequence -> value.toString().trim().takeIf { isUsefulExtraText(it, key) }?.let(out::add)
                is String -> value.trim().takeIf { isUsefulExtraText(it, key) }?.let(out::add)
                is Bundle -> decodeExtrasDeep(value, out, depth + 1)
                is Array<*> -> value.forEach { item ->
                    when (item) {
                        is CharSequence -> item.toString().trim()
                            .takeIf { isUsefulExtraText(it, key) }?.let(out::add)
                        is String -> item.trim().takeIf { isUsefulExtraText(it, key) }?.let(out::add)
                    }
                }
                is ArrayList<*> -> value.forEach { item ->
                    when (item) {
                        is CharSequence -> item.toString().trim()
                            .takeIf { isUsefulExtraText(it, key) }?.let(out::add)
                        is String -> item.trim().takeIf { isUsefulExtraText(it, key) }?.let(out::add)
                        is Bundle -> decodeExtrasDeep(item, out, depth + 1)
                    }
                }
            }
        }
    }

    fun logExtrasSummary(extras: Bundle, prefix: String = "notif extras") {
        val summary = buildString {
            append("$prefix: keys=[")
            append(extras.keySet().sorted().joinToString(", "))
            append("] topFullscreen=${isTopFullscreen(extras)} ")
            append("sample=")
            val samples = linkedSetOf<String>()
            decodeExtrasDeep(extras, samples)
            append(samples.take(8).joinToString(" | "))
        }
        HudLog.i(summary.take(900))
    }

    private fun isUsefulExtraText(text: String, key: String): Boolean {
        if (text.isBlank() || text.length > 500) return false
        val lower = text.lowercase()
        val keyLower = key.lowercase()
        if (lower in setOf("true", "false", "phone", "floating_window_notification")) return false
        if (keyLower in SKIP_EXTRA_KEYS) return false
        if (keyLower.startsWith("android.") && keyLower !in ANDROID_EXTRA_KEYS) return false
        return true
    }

    private val SKIP_EXTRA_KEYS = setOf(
        "specialtype", "reminddevice", "externalchanneltype", "remindertype",
        "popupbackgroundwindowprevilege", "topfullscreen", "isrequestsingline",
        "gamedndon", "notification_should_ringtone", "hw_enable_small_icon"
    )

    private val ANDROID_EXTRA_KEYS = setOf(
        "android.title", "android.text", "android.subtext", "android.bigtext",
        "android.infoText", "android.summaryText", "android.textlines",
        "android.title.big", "android.textlines"
    )

    fun dedupeTexts(lines: Collection<String>): List<String> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val key = normalizeKey(line)
            if (key.isEmpty() || key in seen) continue
            if (out.any { existing -> isDuplicateLine(existing, line) }) continue
            seen.add(key)
            out.add(line)
        }
        return out
    }

    fun normalizeKey(text: String): String =
        text.trim().lowercase()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '!', '?', '…')

    private fun isDuplicateLine(existing: String, candidate: String): Boolean {
        val a = normalizeKey(existing)
        val b = normalizeKey(candidate)
        if (a == b) return true
        if (a.length >= 4 && b.contains(a)) return true
        if (b.length >= 4 && a.contains(b)) return true
        return false
    }

    private fun decodeRemoteViews(
        context: Context,
        remoteViews: RemoteViews,
        texts: LinkedHashSet<String>,
        icons: MutableList<Bitmap>
    ) {
        val host = FrameLayout(context)
        val applied = try {
            @Suppress("DEPRECATION")
            remoteViews.apply(context, host)
        } catch (e: Exception) {
            HudLog.w("RemoteViews.apply failed: ${e.message}")
            null
        } ?: return
        walkView(applied, texts, icons)
    }

    private fun walkView(view: View, texts: LinkedHashSet<String>, icons: MutableList<Bitmap>) {
        when (view) {
            is TextView -> {
                view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
                view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            }
            is ImageView -> bitmapFromImageView(view)?.let { icons.add(it) }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkView(view.getChildAt(i), texts, icons)
            }
        }
    }

    private fun bitmapFromImageView(view: ImageView): Bitmap? {
        val drawable = view.drawable ?: return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else view.width.coerceAtLeast(1)
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else view.height.coerceAtLeast(1)
        if (width <= 0 || height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
