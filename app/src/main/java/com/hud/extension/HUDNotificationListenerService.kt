package com.hud.extension

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.ByteArrayOutputStream

class HUDNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Логируем ВСЕ уведомления для отладки, если ничего не приходит
        Log.d("HUD_SCAN", "Notification received from: $packageName")

        if (packageName.contains("huawei", ignoreCase = true) || packageName.contains("yandex", ignoreCase = true)) {
            val notification = sbn.notification
            val extras = notification.extras
            
            Log.d("HUD_DEBUG", "--- DETAILED SCAN: $packageName ---")
            for (key in extras.keySet()) {
                try {
                    val value = extras.get(key)
                    Log.d("HUD_DEBUG", "Key: $key | Value: $value")
                } catch (e: Exception) {
                    Log.e("HUD_DEBUG", "Error reading key $key")
                }
            }

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

            // Для Petal Maps часто важно проверить заголовок, если текст пустой
            Log.d("HUD_DATA", "Package: $packageName | Title: $title | Text: $text | Sub: $subText")

            val intent = Intent("com.hud.extension.NAV_UPDATE")
            intent.putExtra("package", packageName)
            
            // Если текст пустой, но есть заголовок - берем заголовок
            val finalTitle = if (title.isNotEmpty()) title else packageName
            val finalText = when {
                text.isNotEmpty() && !text.contains("Navigating", ignoreCase = true) -> text
                bigText.isNotEmpty() -> bigText
                subText.isNotEmpty() -> subText
                else -> text // Оставляем как есть, если больше ничего нет
            }

            intent.putExtra("title", finalTitle)
            intent.putExtra("text", finalText)
            
            // Пытаемся взять любую иконку
            val icon = notification.getLargeIcon() ?: notification.smallIcon
            icon?.let {
                val bitmap = iconToBitmap(it)
                bitmap?.let { b ->
                    val stream = ByteArrayOutputStream()
                    b.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    intent.putExtra("icon", stream.toByteArray())
                }
            }
            
            sendBroadcast(intent)
        }
    }

    private fun iconToBitmap(icon: Icon): Bitmap? {
        try {
            val drawable = icon.loadDrawable(this) ?: return null
            if (drawable is BitmapDrawable) return drawable.bitmap
            
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            Log.e("HUDNotification", "Icon error: ${e.message}")
            return null
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg.contains("huawei") || pkg.contains("yandex")) {
            val intent = Intent("com.hud.extension.NAV_UPDATE")
            intent.putExtra("command", "clear")
            sendBroadcast(intent)
        }
    }
}
