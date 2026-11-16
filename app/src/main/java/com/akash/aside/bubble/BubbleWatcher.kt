package com.akash.aside.bubble

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.akash.aside.AppScope
import com.akash.aside.cloud.Sync
import com.akash.aside.data.AnswerDao
import com.akash.aside.data.PhoneAnswer
import com.akash.aside.overlay.DotController
import com.akash.aside.usage.RawEvent
import com.akash.aside.usage.SessionBuilder
import com.akash.aside.usage.UsageCollector
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Colors the ring around the dot. While the screen is on it looks at the phone's foreground-app log every few
 * seconds (the Usage access the app already has; no accessibility service) and adds up the time on flagged apps in the
 * last 30 minutes. The rules themselves live in [BubbleLogic]. Nothing here makes a sound or posts a notification.
 */
class BubbleWatcher(
    private val ctx: Context,
    private val dot: DotController,
    private val answers: AnswerDao,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    private val raw = ArrayList<RawEvent>()
    private var lastQuery = 0L
    private var current = BubbleLogic.Ring.NONE
    private var distraction = BubbleLogic.Ring.NONE
    private var activePrompt = false
    private var byApp: Map<String, Long> = emptyMap()
    private var sheet: BubbleLogic.Sheet? = null
    private var taskCheck: PlanBlock? = null
    private var debrief: PlanBlock? = null
    private var anomaly = false
    private var running = false

    private val pollTask = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON) startPolling() else stopPolling()
        }
    }

    fun start() {
        ctx.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        if (power.isInteractive) startPolling()
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(screen) }
        stopPolling()
        dot.setRing(null)
    }

    private fun startPolling() {
        if (running) return
        running = true
        handler.post(pollTask)
    }

    private fun stopPolling() {
        running = false
        handler.removeCallbacks(pollTask)
    }

    /** Look again now (after an answer, or after a sync brought something new). */
    fun refresh() = poll()

    // --- the poll ---

    private fun poll() {
        val nowMs = System.currentTimeMillis()
        val zdt = ZonedDateTime.now()
        val day = BubbleLogic.logicalDay(zdt)

        var d = BubbleLogic.Ring.NONE
        if (BubbleStore.enabled(ctx) && UsageCollector.hasAccess(ctx)) {
            readEvents(nowMs)
            val since = maxOf(nowMs - BubbleLogic.WINDOW_MS, BubbleStore.ringResetAt(ctx))
            val result = SessionBuilder({ it.isEmpty() }).build(raw)
            byApp = BubbleLogic.flaggedByApp(result, BubbleStore.flagged(ctx), nowMs, since)
            d = BubbleLogic.distractionRing(byApp.values.sum())
        } else {
            byApp = emptyMap()
        }

        // At most a few distraction prompts a day: a new one counts when the ring first rises from neutral.
        if (d != BubbleLogic.Ring.NONE && !activePrompt) {
            if (BubbleLogic.mayPrompt(BubbleStore.promptsToday(ctx, day))) {
                activePrompt = true
                BubbleStore.addPrompt(ctx, day)
            } else d = BubbleLogic.Ring.NONE
        } else if (d == BubbleLogic.Ring.NONE) activePrompt = false
        val rose = d > distraction && d != BubbleLogic.Ring.NONE
        distraction = d

        val snoozed = nowMs < BubbleStore.snoozedUntil(ctx)
        anomaly = !snoozed && BubbleStore.anomalyWaiting(ctx)
        sheet = if (snoozed) null else BubbleLogic.sheetWaiting(
            zdt, BubbleStore.morningDone(ctx), BubbleStore.eveningDone(ctx),
            BubbleStore.sleepFor(ctx, zdt.toLocalDate()) != null,
        )

        val plan = BubbleStore.plan(ctx)
        taskCheck = BubbleLogic.taskCheckWaiting(
            plan, nowMs, BubbleStore.taskChecksDone(ctx), BubbleStore.taskChecksSnoozed(ctx)
        )
        debrief = BubbleLogic.debriefWaiting(
            plan, nowMs, BubbleStore.debriefsDone(ctx), BubbleStore.debriefsSnoozed(ctx)
        )

        val ring = BubbleLogic.ring(anomaly, sheet, taskCheck, debrief, distraction)
        if (ring != current) {
            val appeared = current == BubbleLogic.Ring.NONE
            current = ring
            dot.setRing(color(ring))
            if (ring != BubbleLogic.Ring.NONE && appeared) logTap("shown", ring)
        }
        if (rose && current != BubbleLogic.Ring.BLUE) dot.tick()
    }

    /** Only what is new since the last look (with a few seconds of overlap, which the session builder tolerates). */
    private fun readEvents(nowMs: Long) {
        val from = if (lastQuery == 0L) nowMs - LOOKBACK_MS else lastQuery - 5_000
        raw += UsageCollector.readRaw(usm, from, nowMs)
        lastQuery = nowMs
        val cutoff = nowMs - LOOKBACK_MS
        raw.removeAll { it.timeMs < cutoff }
    }

    private fun color(r: BubbleLogic.Ring): Int? = when (r) {
        BubbleLogic.Ring.NONE -> null
        BubbleLogic.Ring.AMBER -> AMBER
        BubbleLogic.Ring.RED -> RED
        BubbleLogic.Ring.BLUE -> BLUE
    }

    // --- tapping the dot ---

    /** What to ask when the dot is tapped now, or null when nothing is waiting (the input window opens instead). */
    fun currentAsk(): Ask? {
        poll()
        val zdt = ZonedDateTime.now()
        val day = BubbleLogic.logicalDay(zdt)
        val nowMs = System.currentTimeMillis()

        // 1. Time-critical task check (10 min before scheduled task)
        taskCheck?.let { return Ask.TaskCheck(it.title, it.startMs, it.parsedSlipCount) }

        // 2. Time-critical deep work debrief (after deep work block ends)
        debrief?.let { return Ask.Debrief(it.title, it.endMs) }

        // 3. Anomaly question
        BubbleStore.anomalyDay(ctx)?.takeIf { anomaly }?.let { return Ask.Anomaly(it) }

        // 4. Daily sheets
        when (sheet) {
            BubbleLogic.Sheet.MORNING -> {
                val s = BubbleStore.sleepFor(ctx, zdt.toLocalDate())
                return Ask.Sleep(day, s?.startMs, s?.wakeMs)
            }
            BubbleLogic.Sheet.EVENING -> return Ask.Evening(day)
            null -> {}
        }
        if (distraction == BubbleLogic.Ring.NONE) return null
        val plan = BubbleLogic.planNow(BubbleStore.plan(ctx), nowMs)
        val planLine = plan?.let { "Plan now: ${it.title} -> ${Instant.ofEpochMilli(it.endMs).atZone(ZoneId.systemDefault()).format(clock)}" }
            ?: "No plan block right now"
        val pm = ctx.packageManager
        val apps = byApp.entries.sortedByDescending { it.value }
            .joinToString(" + ") { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it.key, 0)).toString() }.getOrDefault(it.key) }
        return Ask.Distraction(planLine, (byApp.values.sum() / 60_000L).toInt(), apps, if (distraction == BubbleLogic.Ring.RED) "red" else "amber")
    }

    /**
     * "Not now": the glow goes away without an answer. A distraction glow starts counting from zero again; a daily sheet or
     * the anomaly question is hidden for a few hours and asked again after. Logged as a receptivity signal.
     */
    fun notNow(ask: Ask) {
        val nowMs = System.currentTimeMillis()
        logTap("dismissed", current)
        when (ask) {
            is Ask.Distraction -> {
                BubbleStore.setRingResetAt(ctx, nowMs)
                activePrompt = false
            }
            is Ask.TaskCheck -> {
                BubbleStore.snoozeTaskCheck(ctx, BubbleLogic.blockKey(ask.title, ask.startMs))
            }
            is Ask.Debrief -> {
                BubbleStore.snoozeDebrief(ctx, BubbleLogic.blockKey(ask.title, ask.endMs))
            }
            else -> {
                BubbleStore.setSnoozedUntil(ctx, nowMs + BubbleLogic.SNOOZE_MS)
            }
        }
        poll()
    }

    /** The dot was tapped while colored: a receptivity signal. */
    fun opened() = logTap("opened", current)

    /** The user answered a sheet: save it for upload, clear what it answered, look again. */
    fun answered(kind: String, payload: JSONObject) {
        val nowMs = System.currentTimeMillis()
        save(kind, payload, nowMs)
        runCatching {
            when (kind) {
                PhoneAnswer.DISTRACTION -> { BubbleStore.setRingResetAt(ctx, nowMs); activePrompt = false }
                PhoneAnswer.SLEEP -> BubbleStore.setMorningDone(ctx, LocalDate.parse(payload.getString("day")))
                PhoneAnswer.EVENING -> BubbleStore.setEveningDone(ctx, LocalDate.parse(payload.getString("day")))
                PhoneAnswer.ANOMALY -> BubbleStore.setAnomalyAnswered(ctx, LocalDate.parse(payload.getString("for_day")))
                PhoneAnswer.TASK_CHECK -> {
                    val title = payload.getString("title")
                    val startMs = payload.getLong("block_start")
                    BubbleStore.markTaskCheckDone(ctx, BubbleLogic.blockKey(title, startMs))
                }
                PhoneAnswer.DEBRIEF -> {
                    val title = payload.getString("title")
                    val endMs = payload.getLong("block_end")
                    BubbleStore.markDebriefDone(ctx, BubbleLogic.blockKey(title, endMs))
                }
            }
        }
        poll()
    }

    private fun logTap(event: String, ring: BubbleLogic.Ring) {
        val p = JSONObject().put("event", event).put("ring", ring.name.lowercase())
            .put("hour", ZonedDateTime.now().hour).put("apps", byApp.keys.joinToString(","))
        save(PhoneAnswer.TAP, p, System.currentTimeMillis())
    }

    private fun save(kind: String, payload: JSONObject, atMs: Long) {
        AppScope.launch {
            answers.insert(PhoneAnswer(UUID.randomUUID().toString(), kind, atMs, payload.toString()))
            Sync.kick(ctx)
        }
    }

    companion object {
        private const val POLL_MS = 10_000L
        private const val LOOKBACK_MS = 6L * 60 * 60 * 1000
        val AMBER = 0xFFFFB74D.toInt()
        val RED = 0xFFF28B82.toInt()
        val BLUE = 0xFF4285F4.toInt()
    }
}
