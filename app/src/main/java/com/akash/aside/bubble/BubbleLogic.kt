package com.akash.aside.bubble

import com.akash.aside.data.PhoneAnswer
import com.akash.aside.usage.SessionBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** One block of today's plan, as the phone shows it. */
data class PlanBlock(val title: String, val startMs: Long, val endMs: Long)

interface AnswerRemote {
    suspend fun uploadAnswers(batch: List<PhoneAnswer>)
}

/**
 * The rules for the colored ring around the dot, free of Android so they are tested on the JVM.
 * Neutral: no ring. Amber / red: time on flagged apps in the last 30 minutes. Blue: a question sheet is waiting.
 * One color at a time; priority is anomaly, then the daily sheets, then distraction.
 */
object BubbleLogic {
    const val WINDOW_MS = 30 * 60_000L
    /** How long "Not now" hides a daily sheet or the anomaly question. */
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

    fun ring(anomalyWaiting: Boolean, sheet: Sheet?, distraction: Ring): Ring = when {
        anomalyWaiting || sheet != null -> Ring.BLUE
        else -> distraction
    }

    /** Whether a ring that just appeared may count as a new prompt, given today's count. */
    fun mayPrompt(promptsToday: Int) = promptsToday < MAX_PROMPTS_PER_DAY

    fun planNow(blocks: List<PlanBlock>, nowMs: Long): PlanBlock? = blocks.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }
}
