package com.newlife.tv.record

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.newlife.tv.NewLifeApp
import com.newlife.tv.R
import com.newlife.tv.data.L
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.data.ScheduledRecording
import com.newlife.tv.net.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RecordingService : Service() {

    data class Active(val id: String, val title: String, val path: String, val bytes: Long, val endsAt: Long)

    companion object {
        private const val ACTION_NOW = "com.newlife.tv.REC_NOW"
        private const val ACTION_SCHEDULED = "com.newlife.tv.REC_SCHEDULED"
        private const val ACTION_STOP = "com.newlife.tv.REC_STOP"
        private const val NOTIF_ID = 4242
        private val gson = Gson()

        val active = MutableStateFlow<Map<String, Active>>(emptyMap())

        fun startNow(ctx: Context, req: PlayRequest, title: String, minutes: Int): String {
            val id = UUID.randomUUID().toString()
            val i = Intent(ctx, RecordingService::class.java).setAction(ACTION_NOW)
                .putExtra("id", id).putExtra("req", gson.toJson(req)).putExtra("title", title).putExtra("minutes", minutes)
            ContextCompat.startForegroundService(ctx, i)
            return id
        }

        fun startScheduled(ctx: Context, scheduleId: String) {
            val i = Intent(ctx, RecordingService::class.java).setAction(ACTION_SCHEDULED).putExtra("id", scheduleId)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context, id: String?) {
            val i = Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP).putExtra("id", id)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProfileStore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra("id")
        when (intent?.action) {
            ACTION_NOW -> {
                val req = gson.fromJson(intent.getStringExtra("req"), PlayRequest::class.java)
                val title = intent.getStringExtra("title") ?: req.title
                val minutes = intent.getIntExtra("minutes", 60)
                launchJob(id ?: UUID.randomUUID().toString(), title, System.currentTimeMillis() + minutes * 60_000L, null) { req }
            }
            ACTION_SCHEDULED -> {
                val s = ProfileStore.schedules.value.firstOrNull { it.id == id }
                if (s == null || s.endMs < System.currentTimeMillis()) stopIfIdle()
                else launchJob(s.id, s.title, s.endMs, s.id) {
                    val profile = ProfileStore.profile(s.profileId) ?: throw IOException("Profile removed")
                    val p = ProviderFactory.create(this, profile)
                    p.connect()
                    p.resolve(s.entry)
                }
            }
            ACTION_STOP -> {
                if (id == null) jobs.values.forEach { it.cancel() } else jobs[id]?.cancel()
                stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun launchJob(id: String, title: String, endAt: Long, scheduleId: String?, resolver: suspend () -> PlayRequest) {
        if (jobs.containsKey(id)) return
        scheduleId?.let { ProfileStore.updateSchedule(it, ScheduledRecording.STATUS_RECORDING) }
        jobs[id] = scope.launch {
            try {
                val req = resolver()
                val file = RecordingStore.newFile(this@RecordingService, title, req.url)
                active.update { it + (id to Active(id, title, file.absolutePath, 0, endAt)) }
                notifyProgress(force = true)
                Recorder.record(req, file, endAt) { bytes ->
                    active.update { m -> m[id]?.let { a -> m + (id to a.copy(bytes = bytes)) } ?: m }
                    notifyProgress(force = false)
                }
                scheduleId?.let { ProfileStore.updateSchedule(it, ScheduledRecording.STATUS_DONE) }
            } catch (e: CancellationException) {
                scheduleId?.let { ProfileStore.updateSchedule(it, ScheduledRecording.STATUS_STOPPED) }
            } catch (e: Exception) {
                scheduleId?.let { ProfileStore.updateSchedule(it, ScheduledRecording.STATUS_FAILED) }
                notifyError(title, e.message ?: e.toString())
            } finally {
                jobs.remove(id)
                active.update { it - id }
                if (jobs.isEmpty()) stopIfIdle() else notifyProgress(force = true)
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val items = active.value.values
        val text = if (items.isEmpty()) L.t("جارٍ التحضير…", "Preparing…")
        else items.joinToString("\n") { "● ${it.title}  ${L.size(it.bytes)}  ⏱ ${L.hm(it.endsAt)}" }
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NewLifeApp.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("NEW LIFE TV • " + L.t("تسجيل", "Recording") + " (${items.size})")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, L.t("إيقاف الكل", "Stop all"), stopPi)
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun notifyProgress(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 3000) return
        lastNotify = now
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun notifyError(title: String, msg: String) {
        val n = NotificationCompat.Builder(this, NewLifeApp.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(L.t("فشل التسجيل", "Recording failed") + ": $title")
            .setContentText(msg)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(title.hashCode(), n)
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        active.value = emptyMap()
        super.onDestroy()
    }
}

object RecordingScheduler {
    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(), Intent(ctx, RecordingAlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    fun schedule(ctx: Context, s: ScheduledRecording) {
        val at = maxOf(s.startMs, System.currentTimeMillis() + 2000)
        val am = am(ctx)
        val pi = pending(ctx, s.id)
        when {
            Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else -> am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: String) = am(ctx).cancel(pending(ctx, id))

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        ProfileStore.schedules.value
            .filter { it.status == ScheduledRecording.STATUS_SCHEDULED && it.endMs > now }
            .forEach { schedule(ctx, it) }
    }
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProfileStore.init(context)
        val id = intent.getStringExtra("id") ?: return
        try {
            RecordingService.startScheduled(context, id)
        } catch (e: Exception) {
            ProfileStore.updateSchedule(id, ScheduledRecording.STATUS_FAILED)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ProfileStore.init(context)
        RecordingScheduler.rescheduleAll(context)
    }
}
