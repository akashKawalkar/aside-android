package com.akash.aside.bubble

import android.content.Context
import com.akash.aside.cloud.SupabaseClient
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The read-only questions the bubble asks the cloud with every sync: today's and tomorrow's plan blocks (for the
 * "Plan now" line), the sleep guess (until it is answered for today) and whether the anomaly question is due.
 * Each is independent and failing quietly: the bubble works from what it last stored.
 */
object PlanFetch {
    suspend fun run(ctx: Context, client: SupabaseClient, now: ZonedDateTime = ZonedDateTime.now()) {
        val today: LocalDate = now.toLocalDate()
        runCatching { BubbleStore.setPlan(ctx, client.planBlocks(today) + client.planBlocks(today.plusDays(1))) }
        // The server can only guess once there was activity after the quiet stretch, so ask each sync until it answers.
        if (now.hour in 5..13 && BubbleStore.sleepFor(ctx, today) == null) {
            runCatching { client.sleepGuess(today)?.let { BubbleStore.setSleep(ctx, today, it.first, it.second) } }
        }
        runCatching { BubbleStore.setAnomalyDay(ctx, client.anomalyDue()) }
    }
}
