package com.mastifdogo.devotion.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Background sync while the app is closed (Every hour / Every 6 hours / Once a day). It always
 * reports success: failures are recorded for Settings' "last error" line, and the daily run
 * schedules the next one.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            BackgroundSync(applicationContext).run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // BackgroundSync records its own failures; nothing may crash the worker.
        }
        if (inputData.getBoolean(KEY_DAILY, false)) {
            try {
                SyncScheduler.enqueueNextDaily(applicationContext)
            } catch (e: Exception) {
                // Rescheduled again on the next app start (SyncScheduler.apply).
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_DAILY = "daily"
    }
}
