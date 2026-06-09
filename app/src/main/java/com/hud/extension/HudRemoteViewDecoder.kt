package com.hud.extension

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView

/** Debug: decode 2GIS notification RemoteViews (tag HUD_2GIS — not the 2GIS app log tag). */
object HudRemoteViewDecoder {

    const val TAG = "HUD_2GIS"

    fun logNotification(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        Log.i(TAG, "posted id=${sbn.id} tag=${sbn.tag} pkg=${sbn.packageName}")
        Log.i(TAG, "TEXT=${extras.getCharSequence(Notification.EXTRA_TEXT)}")
        Log.i(TAG, "TITLE=${extras.getCharSequence(Notification.EXTRA_TITLE)}")
        Log.i(TAG, "BIG_TEXT=${extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}")
        Log.i(TAG, "SUBTEXT=${extras.getCharSequence(Notification.EXTRA_SUB_TEXT)}")
        Log.i(TAG, "TICKER=${n.tickerText}")
        Log.i(TAG, "extras keys=${extras.keySet().sorted().joinToString(", ")}")
        HudLog.i(
            "2GIS notif id=${sbn.id} text=${extras.getCharSequence(Notification.EXTRA_TEXT)} " +
                "title=${extras.getCharSequence(Notification.EXTRA_TITLE)}"
        )
        dump(
            sbn.packageName,
            n.bigContentView ?: n.contentView
        )
    }

    fun dump(packageName: String, remoteViews: RemoteViews?) {
        if (remoteViews == null) {
            Log.i(TAG, "RemoteViews=null pkg=$packageName")
            return
        }
        Log.i(
            TAG,
            "RemoteViews pkg=$packageName layout=${remoteViews.layoutId} " +
                "viewPackage=${remoteViews.`package`}"
        )
        dumpActions(remoteViews)
    }

    fun dump(context: Context, remoteViews: RemoteViews?) {
        if (remoteViews == null) {
            Log.i(TAG, "RemoteViews=null")
            return
        }
        Log.i(TAG, "RemoteViews layout=${remoteViews.layoutId} package=${remoteViews.`package`}")
        dumpActions(remoteViews)
        val host = FrameLayout(context)
        val applied = try {
            @Suppress("DEPRECATION")
            remoteViews.apply(context, host)
        } catch (e: Exception) {
            Log.w(TAG, "RemoteViews.apply failed: ${e.message}")
            null
        }
        if (applied == null) return
        walkView(applied, depth = 0)
    }

    private fun dumpActions(remoteViews: RemoteViews) {
        runCatching {
            val field = RemoteViews::class.java.getDeclaredField("mActions")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val actions = field.get(remoteViews) as? List<*>
            if (actions.isNullOrEmpty()) {
                Log.i(TAG, "mActions: empty")
                return
            }
            actions.forEachIndexed { index, action ->
                Log.i(TAG, "mActions[$index]=${action?.javaClass?.simpleName}: $action")
            }
        }.onFailure { error ->
            Log.i(TAG, "mActions unavailable: ${error.message}")
        }
    }

    private fun walkView(view: View, depth: Int) {
        val indent = "  ".repeat(depth)
        when (view) {
            is TextView -> {
                val text = view.text?.toString()?.trim().orEmpty()
                val desc = view.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) Log.i(TAG, "${indent}TextView id=${view.id} text=$text")
                if (desc.isNotEmpty()) Log.i(TAG, "${indent}TextView id=${view.id} desc=$desc")
            }
            is ImageView -> {
                val drawable = view.drawable
                Log.i(
                    TAG,
                    "${indent}ImageView id=${view.id} " +
                        "drawable=${drawable?.javaClass?.simpleName} " +
                        "size=${view.width}x${view.height}"
                )
            }
            else -> Log.i(TAG, "${indent}${view.javaClass.simpleName} id=${view.id}")
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkView(view.getChildAt(i), depth + 1)
            }
        }
    }
}
