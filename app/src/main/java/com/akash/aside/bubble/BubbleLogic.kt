package com.akash.aside.bubble

import com.akash.aside.data.PhoneAnswer
import com.akash.aside.usage.SessionBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** One block of today's plan, as the phone shows it. */
data class PlanBlock(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val taskId: Int? = null,
    val slipCount: Int = 0,
) {
    val durationMs: Long get() = maxOf(0L, endMs - startMs)

    fun isAnchorOrBreak(): Boolean {
        val t = title.lowercase()
        return t.contains("workout") || t.contains("exercise") || t.contains("gym") ||
                t.contains("meditation") || t.contains("lunch") || t.contains("dinner") ||
                t.contains("breakfast") || t.contains("walk") || t.contains("break") ||
                t.contains("chores") || t.contains("wind down") || t.contains("sleep") ||
                t.contains("sing") || t.contains("nap")
    }

    fun isDeepWork(): Boolean {
        if (isAnchorOrBreak()) return false
        val t = title.lowercase()
        if (t.contains("deep work") || t.contains("deepwork") || t.startsWith("project") ||
            t.contains("coding") || t.contains("study") || t.contains("research") || t.contains("architecture")) {
            return true
        }
        return durationMs >= 60 * 60_000L
    }

    fun isTask(): Boolean {
        if (isAnchorOrBreak()) return false
        return !isDeepWork() || taskId != null || title.contains("task", ignoreCase = true)
    }

    val parsedSlipCount: Int get() {
        if (slipCount > 0) return slipCount
        val m = Regex("""slipped\s+(\d+)x?""", RegexOption.IGNORE_CASE).find(title)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

interface AnswerRemote {
    suspend fun uploadAnswers(batch: List<PhoneAnswer>)
}

/**
 * The rules for the colored ring around the dot, free of Android so they are tested on the JVM.
 * Neutral: no ring. Amber / red: time on flagged apps in the last 30 minutes. Blue: a question sheet is waiting.
 * One color at a time; priority is task check / debrief / anomaly, then the daily sheets, then distraction.
 */
object BubbleLogic {
    const val WINDOW_MS = 30 * 60_000L
    const val TASK_CHECK_WINDOW_BEFORE_MS = 10 * 60_000L
    const val TASK_CHECK_GRACE_AFTER_MS = 5 * 60_000L
    const val DEBRIEF_WINDOW_AFTER_MS = 30 * 60_000L

    fun blockKey(title: String, timeMs: Long): String = "${title.trim()}_$timeMs"
    fun blockKey(b: PlanBlock): String = blockKey(b.title, b.startMs)
    fun debriefKey(b: PlanBlock): String = blockKey(b.title, b.endMs)
    /** How long "Not now" hides a daily sheet or the anomaly question. */
    /**
     * A bedtime typed as a clock time, as the moment it means for the day (05:00 to 05:00) the evening sheet is about:
     * before 05:00 it is after midnight, so it belongs to the next calendar date.
     */
    fun intendedSleep(day: java.time.LocalDate, t: java.time.LocalTime, zone: java.time.ZoneId): java.time.Instant {
        val date = if (t.isBefore(java.time.LocalTime.of(5, 0))) day.plusDays(1) else day
        return date.atTime(t).atZone(zone).toInstant()
    }

    const val SNOOZE_MS = 3 * 60 * 60_000L
    const val AMBER_MS = 10 * 60_000L
    const val RED_MS = 30 * 60_000L
    const val MAX_PROMPTS_PER_DAY = 3
    const val EVENING_FROM_HOUR = 20

    /** Without a sleep guess the morning sheet still appears from this hour, with empty fields. */
    const val MORNING_FALLBACK_HOUR = 11

    val DEFAULT_FLAGGED = setOf(
        "com.instagram.android", "com.instagram.lite", "com.google.android.youtube",
        "com.facebook.katana", "com.facebook.lite",
    )

    enum class Ring { NONE, AMBER, RED, BLUE }
    enum class Sheet { MORNING, EVENING }

    /** The day runs 05:00 to 05:00: 03:00 on the 11th still belongs to the 10th. */
    fun logicalDay(now: ZonedDateTime): LocalDate = now.minusHours(5).toLocalDate()

    fun logicalDay(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        logicalDay(java.time.Instant.ofEpochMilli(nowMs).atZone(zone))

    /** Milliseconds per flagged app inside [sinceMs, nowMs], the app still in front counted up to now. */
    fun flaggedByApp(r: SessionBuilder.Result, flagged: Set<String>, nowMs: Long, sinceMs: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        fun add(pkg: String, start: Long, end: Long) {
            if (pkg !in flagged) return
            val ms = minOf(end, nowMs) - maxOf(start, sinceMs)
            if (ms > 0) out[pkg] = (out[pkg] ?: 0L) + ms
        }
        for (s in r.closed) add(s.pkg, s.startMs, s.endMs)
        val pkg = r.openPkg
        val since = r.openSince
        if (pkg != null && since != null) add(pkg, since, nowMs)
        return out
    }

    fun distractionRing(flaggedMs: Long): Ring = when {
        flaggedMs >= RED_MS -> Ring.RED
        flaggedMs >= AMBER_MS -> Ring.AMBER
        else -> Ring.NONE
    }

    /** Which daily sheet is waiting right now, if any. Unanswered stays unknown; nothing is ever marked missed. */
    fun sheetWaiting(now: ZonedDateTime, morningDone: LocalDate?, eveningDone: LocalDate?, haveSleepGuess: Boolean): Sheet? {
        val day = logicalDay(now)
        val h = now.hour
        return when {
            h >= EVENING_FROM_HOUR || h < 5 -> if (eveningDone == day) null else Sheet.EVENING
            morningDone == day -> null
            haveSleepGuess || h >= MORNING_FALLBACK_HOUR -> Sheet.MORNING
            else -> null
        }
    }

    fun ring(anomalyWaiting: Boolean, sheet: Sheet?, distraction: Ring): Ring =
        ring(anomalyWaiting, sheet, null, null, distraction)

    fun ring(
        anomalyWaiting: Boolean,
        sheet: Sheet?,
        taskCheck: PlanBlock?,
        debrief: PlanBlock?,
        distraction: Ring,
    ): Ring = when {
        taskCheck != null || debrief != null || anomalyWaiting || sheet != null -> Ring.BLUE
        else -> distraction
    }

    /**
     * Checks if a scheduled task is 10 minutes away (or within 5 min after start),
     * asking "Where does it stand?".
     */
    fun taskCheckWaiting(
        blocks: List<PlanBlock>,
        nowMs: Long,
        answeredKeys: Set<String>,
        snoozedKeys: Set<String>,
    ): PlanBlock? = blocks.firstOrNull { b ->
        val key = blockKey(b)
        key !in answeredKeys && key !in snoozedKeys &&
            b.isTask() &&
            nowMs >= (b.startMs - TASK_CHECK_WINDOW_BEFORE_MS) &&
            nowMs < (b.startMs + TASK_CHECK_GRACE_AFTER_MS)
    }

    /**
     * Checks if a deep work session ended in the last 30 minutes, asking for debrief.
     */
    fun debriefWaiting(
        blocks: List<PlanBlock>,
        nowMs: Long,
        answeredKeys: Set<String>,
        snoozedKeys: Set<String>,
    ): PlanBlock? = blocks.lastOrNull { b ->
        val key = debriefKey(b)
        key !in answeredKeys && key !in snoozedKeys &&
            b.isDeepWork() &&
            nowMs >= b.endMs &&
            nowMs < (b.endMs + DEBRIEF_WINDOW_AFTER_MS)
    }

    /** Whether a ring that just appeared may count as a new prompt, given today's count. */
    fun mayPrompt(promptsToday: Int) = promptsToday < MAX_PROMPTS_PER_DAY

    fun planNow(blocks: List<PlanBlock>, nowMs: Long): PlanBlock? = blocks.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }
}
