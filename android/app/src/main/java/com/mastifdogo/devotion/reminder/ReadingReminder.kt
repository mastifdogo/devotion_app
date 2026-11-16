package com.mastifdogo.devotion.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mastifdogo.devotion.MainActivity
import com.mastifdogo.devotion.R
import com.mastifdogo.devotion.data.ReadStore
import com.mastifdogo.devotion.data.SettingsStore
import com.mastifdogo.devotion.data.readCachedEntries
import com.mastifdogo.devotion.logic.ReminderDecision
import com.mastifdogo.devotion.logic.ReminderLogic
import com.mastifdogo.devotion.logic.ServerAddress
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The "Today's reading is waiting" reminder: about every 15 minutes (WorkManager's minimum), while
 * the latest entry is unread and it is not quiet hours, one notification that is replaced rather
 * than stacked, and removed as soon as the entry is read. It needs no network: it looks at the
 * entries and read marks cached on the phone.
 */
object ReadingReminder {
    const val CHANNEL_ID = "reading_reminders"
    const val NOTIFICATION_ID = 4101
    private const val WORK_NAME = "devotion-reading-reminder"

    /** Extra on the launch intent from the reminder: the id of the entry to open. */
    const val EXTRA_OPEN_ENTRY = "com.mastifdogo.devotion.OPEN_ENTRY"

    /** Starts or stops the periodic check to match the setting; KEEP so an existing schedule is not reset. */
    fun schedule(context: Context) {
        val settings = SettingsStore(context)
        val workManager = WorkManager.getInstance(context)
        if (settings.remindersEnabled) {
            val request = PeriodicWorkRequest.Builder(ReminderWorker::class.java, 15, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            workManager.cancelUniqueWork(WORK_NAME)
            cancel(context)
        }
    }

    /**
     * Decides from what is on disk whether a reminder should show right now, and posts or removes
     * it. Blocking (reads files); call off the main thread. Never throws.
     */
    fun evaluate(context: Context) {
        try {
            val app = context.applicationContext
            val settings = SettingsStore(app)
            val key = ServerAddress.key(settings.serverUrl)
            val profileId = settings.profileFor(key)?.id ?: 0L
            val now = LocalTime.now()
            val decision = ReminderLogic.decide(
                enabled = settings.remindersEnabled,
                entries = readCachedEntries(app.filesDir),
                readIds = ReadStore(app.filesDir).load(key, profileId),
                nowMinutes = now.hour * 60 + now.minute,
                quietFrom = settings.quietFromMinutes,
                quietUntil = settings.quietUntilMinutes,
            )
            when (decision) {
                is ReminderDecision.Notify -> post(app, decision.entryId, decision.title)
                is ReminderDecision.Cancel -> cancel(app)
                is ReminderDecision.Quiet -> Unit
            }
        } catch (e: Exception) {
            // A reminder must never crash the app or the worker.
        }
    }

    fun cancel(context: Context) {
        try {
            manager(context).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // Nothing to do.
        }
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun post(context: Context, entryId: Long, title: String) {
        val manager = manager(context)
        // Creating a channel that exists is a no-op.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ENTRY, entryId)
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = ReminderLogic.notificationText(title)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_devotion)
            .setContentTitle("Devotion")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // Without the notification permission (Android 13+) this simply shows nothing.
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** The periodic check behind [ReadingReminder]. */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        ReadingReminder.evaluate(applicationContext)
        return Result.success()
    }
}
