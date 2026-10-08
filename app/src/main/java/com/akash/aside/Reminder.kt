package com.akash.aside

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** One reminder, 15 minutes after the dot was hidden. It never repeats. */
object ReminderScheduler {
    private const val NAME = "dot_reminder"

    fun scheduleIn15(ctx: Context) {
        if (!Prefs.wantDot(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(15, TimeUnit.MINUTES).build(),
        )
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        Notifications.clearReminder(ctx)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val granted = applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (Prefs.wantDot(applicationContext) && granted) {
            Notifications.ensureChannels(applicationContext)
            Notifications.postReminder(applicationContext)
        }
        return Result.success()
    }
}
