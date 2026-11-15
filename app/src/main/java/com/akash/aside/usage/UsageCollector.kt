package com.akash.aside.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import com.akash.aside.Prefs
import com.akash.aside.data.UsageDao
import java.time.LocalDate
import java.time.ZoneId

/** Reads which app was in front, from the phone's own usage log, into the local store. Foreground apps only: no titles, no content. */
class UsageCollector(private val ctx: Context, private val dao: UsageDao) {

    suspend fun collect() {
        if (!Prefs.usageEnabled(ctx) || !hasAccess(ctx)) return
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val from = Prefs.usageCursor(ctx).takeIf { it > 0 } ?: startOfToday

        val raw = readRaw(usm, from, now)

        val ignored = ignoredSet(ctx)
        val result = SessionBuilder({ it.isEmpty() || it in ignored }).build(raw)
        dao.insert(result.closed.map(SessionBuilder::toSession))
        // Next time start again at the app still in front (if any), so its session is whole when it ends.
        val next = result.openSince ?: (raw.maxOfOrNull { it.timeMs }?.plus(1) ?: from)
        Prefs.setUsageCursor(ctx, next)
    }

    data class AppInfo(val pkg: String, val label: String)

    companion object {
        /** The phone's foreground events between two moments, boiled down to what the session builder needs. */
        fun readRaw(usm: UsageStatsManager, from: Long, to: Long): List<RawEvent> {
            val raw = ArrayList<RawEvent>()
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val type = when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> RawEvent.Type.RESUME
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> RawEvent.Type.PAUSE
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> RawEvent.Type.SCREEN_OFF
                    else -> continue
                }
                raw += RawEvent(e.timeStamp, type, e.packageName.orEmpty())
            }
            return raw
        }

        fun hasAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        }

        /** Banking and payment apps are ignored by default; the person can change the list. */
        private val DEFAULT_KEYWORDS = listOf(
            "bank", "paytm", "phonepe", "paisa", "bhim", "upi", "hdfc", "icici", "sbi", "axis", "kotak", "yono",
            "mobikwik", "freecharge", "groww", "zerodha", "cred", "wallet",
        )

        fun launchableApps(ctx: Context): List<AppInfo> {
            val pm = ctx.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
                .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }

        fun defaultIgnored(apps: List<AppInfo>): Set<String> =
            apps.filter { a -> DEFAULT_KEYWORDS.any { k -> a.pkg.lowercase().contains(k) || a.label.lowercase().contains(k) } }
                .mapTo(HashSet()) { it.pkg }

        /** The saved list, set up with the defaults the first time it is needed. */
        fun ignoredSet(ctx: Context): Set<String> {
            Prefs.ignoredApps(ctx)?.let { return it + system(ctx) }
            val seeded = defaultIgnored(launchableApps(ctx))
            Prefs.setIgnoredApps(ctx, seeded)
            return seeded + system(ctx)
        }

        /** Never counted whatever the list says: this app, the launcher, the system bar. */
        private fun system(ctx: Context): Set<String> {
            val home = ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )?.activityInfo?.packageName
            return setOfNotNull(ctx.packageName, home, "com.android.systemui", "android")
        }
    }
}
