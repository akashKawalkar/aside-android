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
import com.akash.aside.usage.UsageCollector
import com.akash.aside.usage.UsageSync
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
        // Usage is read into the phone's own store first, signed in or not, so nothing is lost while waiting.
        runCatching { UsageCollector(applicationContext, app.db.usageDao()).collect() }
        if (!client.configured || !client.signedIn) return Result.success()   // nothing to send until signed in
        val items = SyncEngine(app.db.itemDao(), client).run()
        val usage = UsageSync(app.db.usageDao(), client).run()
        val answers = com.akash.aside.bubble.AnswerSync(app.db.answerDao(), client).run()
        com.akash.aside.watch.Watch.run(applicationContext, client)
        com.akash.aside.bubble.PlanFetch.run(applicationContext, client)
        Prefs.setSyncResult(applicationContext, System.currentTimeMillis(), items.error ?: usage.error ?: answers.error)
        return if (items.retryLater || usage.retryLater || answers.retryLater) Result.retry() else Result.success()
    }
}
