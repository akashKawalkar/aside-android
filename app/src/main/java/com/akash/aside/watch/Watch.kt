package com.akash.aside.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.akash.aside.Notifications
import com.akash.aside.Prefs
import com.akash.aside.cloud.SupabaseClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The phone's two jobs for the night job, run with every sync:
 *  - watchdog: if tomorrow's schedule still does not exist after 21:10 (or today's after 08:00), start the job on GitHub;
 *  - failure notice: if a step failed and its retry failed too, say so once, with the reason.
 */
object Watch {
    private val clock = DateTimeFormatter.ofPattern("d MMM HH:mm")

    suspend fun run(ctx: Context, client: SupabaseClient, now: LocalDateTime = LocalDateTime.now()) {
        runCatching { watchdog(ctx, client, now) }.onFailure { Prefs.setWatchNote(ctx, "Check failed: ${it.message}") }
        runCatching { announceFailure(ctx, client) }
    }

    private suspend fun watchdog(ctx: Context, client: SupabaseClient, now: LocalDateTime) {
        val decision = WatchdogPlan.decide(
            now,
            Prefs.watchEveningDone(ctx)?.let(LocalDate::parse),
            Prefs.watchMorningDone(ctx)?.let(LocalDate::parse),
        ) ?: return
        val token = Prefs.githubToken(ctx)
        if (token.isNullOrBlank()) { Prefs.setWatchNote(ctx, "No GitHub token set, so the night job cannot be started from here"); return }

        val day = if (decision.day == WatchdogPlan.Day.TOMORROW) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val evening = decision.day == WatchdogPlan.Day.TOMORROW
        val stamp = now.format(clock)
        if (client.scheduleReady(day)) {
            Prefs.setWatchDone(ctx, evening, now.toLocalDate().toString())
            Prefs.setWatchNote(ctx, "$stamp: the schedule for $day was ready")
            return
        }
        when (val r = GitHub.dispatch(token, decision.kind)) {
            GitHub.Outcome.Ok -> {
                Prefs.setWatchDone(ctx, evening, now.toLocalDate().toString())
                Prefs.setWatchNote(ctx, "$stamp: no schedule for $day yet, so I started the night job (${decision.kind})")
            }
            is GitHub.Outcome.Rejected -> {
                Prefs.setWatchDone(ctx, evening, now.toLocalDate().toString())    // asking again would not help
                Prefs.setWatchNote(ctx, "$stamp: could not start the night job. ${r.message}")
            }
            is GitHub.Outcome.Transient -> Prefs.setWatchNote(ctx, "$stamp: could not reach GitHub (${r.message}); will try again")
        }
    }

    private suspend fun announceFailure(ctx: Context, client: SupabaseClient) {
        val f = client.latestFailure()
        val seen = Prefs.lastFailureSeen(ctx)
        if (WatchdogPlan.shouldAnnounce(seen, f?.id) && f != null &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            Notifications.ensureChannels(ctx)
            Notifications.postFailure(ctx, f.step, f.error)
        }
        // The first look just notes where things stand; after that only newer failures count.
        if (f != null && f.id > seen) Prefs.setLastFailureSeen(ctx, f.id)
        else if (seen == 0L) Prefs.setLastFailureSeen(ctx, -1L)
    }
}
