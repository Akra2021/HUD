package com.hud.extension

import android.app.Application

class HudApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationAccessHelper.init(this)
        HudLog.i("HudApplication started")
    }
}
