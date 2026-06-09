package com.hud.extension

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display

object DisplayLaunchHelper {

    const val MAIN_DISPLAY_ID = Display.DEFAULT_DISPLAY

    fun activityDisplayId(activity: Activity): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: MAIN_DISPLAY_ID
        } else {
            MAIN_DISPLAY_ID
        }

    fun launchOnMainDisplay(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.launchDisplayId = MAIN_DISPLAY_ID
        }
        HudLog.i("launch on main display (id=$MAIN_DISPLAY_ID) from display=${(context as? Activity)?.let { activityDisplayId(it) }}")
        context.startActivity(intent, options.toBundle())
    }
}
