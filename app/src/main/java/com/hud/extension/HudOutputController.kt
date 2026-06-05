package com.hud.extension

import android.content.Context

/** Stops all HUD output immediately when the master toggle is off. */
object HudOutputController {

    fun stop(context: Context) {
        NotificationAccessHelper.disable(context)
        HudLog.i("HUD output stopped")
    }

    fun isActive(context: Context): Boolean =
        NotificationAccessHelper.isUserListeningEnabled(context)
}
