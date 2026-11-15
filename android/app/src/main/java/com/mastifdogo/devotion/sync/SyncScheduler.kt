package com.mastifdogo.devotion.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.mastifdogo.devotion.data.SettingsStore
import com.mastifdogo.devotion.logic.AutoSync
import com.mastifdogo.devotion.logic.SyncSchedule
import com.mastifdogo.devotion.logic.SyncScheduleLogic
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Puts the background sync for the "Sync automatically" setting into WorkManager. */
object SyncScheduler {
    private const val PERIODIC_WORK = "devotion-sync-periodic"
    private const val DAILY_WORK = "devotion-sync-daily"

    private fun constraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** (Re)schedules the background work for the current setting; Off / When the app opens cancel it. */
    fun apply(context: Context) {
        val settings = SettingsStore(context)
        val mode = AutoSync.fromKey(settings.autoSync)
        val workManager = WorkManager.getInstance(context)
        when (val schedule = SyncScheduleLogic.scheduleFor(mode, settings.dailySyncMinutes)) {
            is SyncSchedule.None -> {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                workManager.cancelUniqueWork(DAILY_WORK)
            }
            is SyncSchedule.Periodic -> {
                workManager.cancelUniqueWork(DAILY_WORK)
                val request = PeriodicWorkRequest.Builder(SyncWorker::class.java, schedule.hours, TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .build()
                workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
            is SyncSchedule.Daily -> {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                enqueueDaily(context, schedule, ExistingWorkPolicy.REPLACE)
            }
        }
    }

    /**
     * Called by the daily worker when it finishes: queues the run for the next day. APPEND_OR_REPLACE
     * (not REPLACE) so the finishing worker does not cancel itself.
     */
    fun enqueueNextDaily(context: Context) {
        val settings = SettingsStore(context)
        val mode = AutoSync.fromKey(settings.autoSync)
        val schedule = SyncScheduleLogic.scheduleFor(mode, settings.dailySyncMinutes)
        if (schedule is SyncSchedule.Daily) enqueueDaily(context, schedule, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueDaily(context: Context, schedule: SyncSchedule.Daily, policy: ExistingWorkPolicy) {
        val delay = SyncScheduleLogic.delayUntilNext(ZonedDateTime.now(), schedule.hour, schedule.minute)
        val request = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .setInputData(Data.Builder().putBoolean(SyncWorker.KEY_DAILY, true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, policy, request)
    }
}
