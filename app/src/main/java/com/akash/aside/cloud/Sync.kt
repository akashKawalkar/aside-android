package com.akash.aside.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.akash.aside.AsideApp
import com.akash.aside.Prefs
import java.util.concurrent.TimeUnit

object Sync {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Send what is waiting, soon. Cheap to call as often as needed: calls merge into one job. */
    fun kick(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync_now", ExistingWorkPolicy.REPLACE, request)
    }

    /** The safety net: every 15 minutes whatever is still waiting is sent. */
    fun schedulePeriodic(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AsideApp
        val client = app.cloud
        if (!client.configured || !client.signedIn) return Result.success()   // nothing to do until signed in
        val outcome = SyncEngine(app.db.itemDao(), client).run()
        Prefs.setSyncResult(applicationContext, System.currentTimeMillis(), outcome.error)
        return if (outcome.retryLater) Result.retry() else Result.success()
    }
}
