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
        lockAdminAfterUpdate()
        installCrashLogger()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_REC, "Recordings / التسجيلات", NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** After every install or update the hidden menus (device data, recordings, VPN) are hidden again: "B". */
    private fun lockAdminAfterUpdate() {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val prefs = getSharedPreferences("app_state", Context.MODE_PRIVATE)
            if (prefs.getLong("version_code", -1L) != code) {
                ProfileStore.updateSettings { it.copy(adminHidden = true) }
                prefs.edit().putLong("version_code", code).apply()
            }
        }
    }

    /** Saves the reason of any crash so it can be shown (and sent) the next time the app opens. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val info = "NEW LIFE TV ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}" +
                    " • Android ${Build.VERSION.RELEASE} • ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Thread: ${t.name}\n\n" + sw.toString().take(6000)
                java.io.File(filesDir, CRASH_FILE).writeText(info)
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CH_REC = "recordings"
        const val CRASH_FILE = "last_crash.txt"
    }
}
