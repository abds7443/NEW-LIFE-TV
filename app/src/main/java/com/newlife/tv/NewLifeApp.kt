package com.newlife.tv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.newlife.tv.data.ProfileStore

class NewLifeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProfileStore.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_REC, "Recordings / التسجيلات", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val CH_REC = "recordings"
    }
}
