package com.akash.aside.watch

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When the phone should check that the schedule exists, and which night job to start if it does not.
 * Evening: from 21:10 (tomorrow's schedule is due by about 21:30). Morning: from 08:00 (a morning schedule beats none).
 * Each check happens once a day; a failed attempt is not counted, so it is tried again at the next sync.
 */
object WatchdogPlan {
    enum class Day { TOMORROW, TODAY }
    data class Decision(val day: Day, val kind: String)

    private val EVENING = LocalTime.of(21, 10)..LocalTime.of(22, 30)
    private val MORNING = LocalTime.of(8, 0)..LocalTime.of(10, 0)

    fun decide(now: LocalDateTime, eveningDone: LocalDate?, morningDone: LocalDate?): Decision? {
        val t = now.toLocalTime()
        val today = now.toLocalDate()
        if (t >= EVENING.start && t < EVENING.endInclusive && eveningDone != today) return Decision(Day.TOMORROW, "nightly")
        if (t >= MORNING.start && t < MORNING.endInclusive && morningDone != today) return Decision(Day.TODAY, "morning_retry")
        return null
    }

    /**
     * A failure is announced once. `lastSeenId` is 0 before the phone has ever looked (then it only notes where things
     * stand, so an old failure is not announced out of the blue), -1 once it has looked and found nothing, else the last id seen.
     */
    fun shouldAnnounce(lastSeenId: Long, failureId: Long?): Boolean = failureId != null && lastSeenId != 0L && failureId > lastSeenId
}
