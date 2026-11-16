package com.akash.aside

import com.akash.aside.bubble.AnswerRemote
import com.akash.aside.bubble.AnswerSync
import com.akash.aside.bubble.BubbleLogic
import com.akash.aside.bubble.BubbleLogic.Ring
import com.akash.aside.bubble.BubbleLogic.Sheet
import com.akash.aside.bubble.PlanBlock
import com.akash.aside.capture.Prefix
import com.akash.aside.cloud.RejectedError
import com.akash.aside.cloud.TransientError
import com.akash.aside.data.AnswerDao
import com.akash.aside.data.Kind
import com.akash.aside.data.PhoneAnswer
import com.akash.aside.usage.RawEvent
import com.akash.aside.usage.RawEvent.Type.RESUME
import com.akash.aside.usage.RawEvent.Type.SCREEN_OFF
import com.akash.aside.usage.SessionBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private const val MIN = 60_000L
private val IST = ZoneId.of("Asia/Kolkata")
private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, IST)

class PlannerPrefixTest {
    @Test fun `p and r are an instruction and a rule`() {
        assertEquals(Kind.PLAN, Prefix.parse("p: no calls after 6").kind)
        assertEquals("no calls after 6", Prefix.parse("p: no calls after 6").text)
        assertEquals(Kind.PLAN, Prefix.parse("plan: go lighter").kind)
        assertEquals(Kind.RULE, Prefix.parse("R: meditate before dinner").kind)
        assertEquals(Kind.RULE, Prefix.parse("rule: no phone in bed").kind)
    }

    @Test fun `ordinary words that start with p or r are not prefixes`() {
        assertNull(Prefix.parse("plan the trip").kind)
        assertNull(Prefix.parse("pick up: milk").kind)
        assertNull(Prefix.parse("reminder: pay rent").kind)
    }
}

class FlaggedTimeTest {
    private val flagged = setOf("ig", "yt")
    private val b = SessionBuilder(ignored = { it.isEmpty() })

    private fun ev(min: Long, type: RawEvent.Type, pkg: String = "") = RawEvent(min * MIN, type, pkg)

    @Test fun `only flagged apps inside the window count`() {
        val r = b.build(listOf(ev(0, RESUME, "ig"), ev(20, RESUME, "notes"), ev(25, RESUME, "yt"), ev(40, SCREEN_OFF)))
        // now = minute 40, window 30m => from minute 10: ig 10-20 (10m) + yt 25-40 (15m)
        val by = BubbleLogic.flaggedByApp(r, flagged, 40 * MIN, 10 * MIN)
        assertEquals(10 * MIN, by["ig"]); assertEquals(15 * MIN, by["yt"]); assertEquals(25 * MIN, by.values.sum())
    }

    @Test fun `the app still in front counts up to now`() {
        val r = b.build(listOf(ev(0, RESUME, "yt")))
        assertEquals(12 * MIN, BubbleLogic.flaggedByApp(r, flagged, 12 * MIN, 0).values.sum())
    }

    @Test fun `time before the last answer is not counted again`() {
        val r = b.build(listOf(ev(0, RESUME, "yt"), ev(20, SCREEN_OFF)))
        // answered at minute 15: only minutes 15-20 are new
        assertEquals(5 * MIN, BubbleLogic.flaggedByApp(r, flagged, 25 * MIN, 15 * MIN).values.sum())
    }

    @Test fun `thresholds`() {
        assertEquals(Ring.NONE, BubbleLogic.distractionRing(9 * MIN))
        assertEquals(Ring.AMBER, BubbleLogic.distractionRing(10 * MIN))
        assertEquals(Ring.AMBER, BubbleLogic.distractionRing(29 * MIN))
        assertEquals(Ring.RED, BubbleLogic.distractionRing(30 * MIN))
    }

    @Test fun `at most three prompts a day`() {
        assertTrue(BubbleLogic.mayPrompt(2)); assertFalse(BubbleLogic.mayPrompt(3))
    }
}

class DailySheetTest {
    private val d10 = LocalDate.of(2026, 11, 10)

    @Test fun `the day runs from 05 00 to 05 00`() {
        assertEquals(d10, BubbleLogic.logicalDay(at(2026, 11, 11, 3, 0)))
        assertEquals(LocalDate.of(2026, 11, 11), BubbleLogic.logicalDay(at(2026, 11, 11, 5, 0)))
    }

    @Test fun `morning sheet waits for a sleep guess or for 11 00`() {
        assertNull(BubbleLogic.sheetWaiting(at(2026, 11, 10, 8), null, null, haveSleepGuess = false))
        assertEquals(Sheet.MORNING, BubbleLogic.sheetWaiting(at(2026, 11, 10, 8), null, null, haveSleepGuess = true))
        assertEquals(Sheet.MORNING, BubbleLogic.sheetWaiting(at(2026, 11, 10, 11), null, null, haveSleepGuess = false))
    }

    @Test fun `an answered morning sheet is gone for the day`() {
        assertNull(BubbleLogic.sheetWaiting(at(2026, 11, 10, 12), d10, null, haveSleepGuess = true))
    }

    @Test fun `evening sheet from 20 00 until 05 00, once per day`() {
        assertNull(BubbleLogic.sheetWaiting(at(2026, 11, 10, 19, 59), d10, null, true))
        assertEquals(Sheet.EVENING, BubbleLogic.sheetWaiting(at(2026, 11, 10, 20), d10, null, true))
        assertEquals(Sheet.EVENING, BubbleLogic.sheetWaiting(at(2026, 11, 11, 2), d10, null, true))
        assertNull(BubbleLogic.sheetWaiting(at(2026, 11, 11, 2), d10, d10, true))
        // the morning sheet of the next day is a new day
        assertEquals(Sheet.MORNING, BubbleLogic.sheetWaiting(at(2026, 11, 11, 11), d10, d10, false))
    }

    @Test fun `blue wins over distraction, one color at a time`() {
        assertEquals(Ring.BLUE, BubbleLogic.ring(true, null, Ring.RED))
        assertEquals(Ring.BLUE, BubbleLogic.ring(false, Sheet.EVENING, Ring.AMBER))
        assertEquals(Ring.RED, BubbleLogic.ring(false, null, Ring.RED))
        assertEquals(Ring.NONE, BubbleLogic.ring(false, null, Ring.NONE))
    }

    @Test fun `plan now picks the block that is running`() {
        val plan = listOf(PlanBlock("Study", 10 * MIN, 40 * MIN), PlanBlock("Walk", 40 * MIN, 60 * MIN))
        assertEquals("Study", BubbleLogic.planNow(plan, 10 * MIN)?.title)
        assertEquals("Walk", BubbleLogic.planNow(plan, 40 * MIN)?.title)
        assertNull(BubbleLogic.planNow(plan, 60 * MIN))
        assertNull(BubbleLogic.planNow(emptyList(), 0))
    }
}

class TaskCheckTest {
    @Test fun `task check appears 10 minutes before task start`() {
        val block = PlanBlock("Renew passport form", 50 * MIN, 80 * MIN)
        val plan = listOf(block)
        // 11 min before: not yet
        assertNull(BubbleLogic.taskCheckWaiting(plan, 39 * MIN, emptySet(), emptySet()))
        // 10 min before: appears
        val tc = BubbleLogic.taskCheckWaiting(plan, 40 * MIN, emptySet(), emptySet())
        assertEquals("Renew passport form", tc?.title)
        // At start time: still waiting
        assertEquals("Renew passport form", BubbleLogic.taskCheckWaiting(plan, 50 * MIN, emptySet(), emptySet())?.title)
        // 4 min after start: still in grace window
        assertEquals("Renew passport form", BubbleLogic.taskCheckWaiting(plan, 54 * MIN, emptySet(), emptySet())?.title)
        // 5 min after start: window closed
        assertNull(BubbleLogic.taskCheckWaiting(plan, 55 * MIN, emptySet(), emptySet()))
    }

    @Test fun `task check ignores anchors and breaks`() {
        val plan = listOf(
            PlanBlock("Morning workout", 50 * MIN, 70 * MIN),
            PlanBlock("Meditation", 70 * MIN, 100 * MIN),
            PlanBlock("Walk outside", 100 * MIN, 120 * MIN),
            PlanBlock("Lunch", 120 * MIN, 150 * MIN),
        )
        assertNull(BubbleLogic.taskCheckWaiting(plan, 45 * MIN, emptySet(), emptySet()))
        assertNull(BubbleLogic.taskCheckWaiting(plan, 65 * MIN, emptySet(), emptySet()))
    }

    @Test fun `task check respects answered and snoozed keys`() {
        val block = PlanBlock("Review pull request", 50 * MIN, 70 * MIN)
        val plan = listOf(block)
        val key = BubbleLogic.blockKey(block)
        assertEquals("Review pull request", BubbleLogic.taskCheckWaiting(plan, 45 * MIN, emptySet(), emptySet())?.title)
        assertNull(BubbleLogic.taskCheckWaiting(plan, 45 * MIN, setOf(key), emptySet()))
        assertNull(BubbleLogic.taskCheckWaiting(plan, 45 * MIN, emptySet(), setOf(key)))
    }

    @Test fun `task check parses slip count from title or explicit property`() {
        val b1 = PlanBlock("Renew passport form (slipped 3x)", 10 * MIN, 30 * MIN)
        assertEquals(3, b1.parsedSlipCount)
        val b2 = PlanBlock("Renew passport form", 10 * MIN, 30 * MIN, slipCount = 4)
        assertEquals(4, b2.parsedSlipCount)
        val b3 = PlanBlock("Normal task", 10 * MIN, 30 * MIN)
        assertEquals(0, b3.parsedSlipCount)
    }
}

class DebriefTest {
    @Test fun `debrief appears after deep work block ends`() {
        val deepWork = PlanBlock("Project coding: API endpoints", 60 * MIN, 180 * MIN)
        val plan = listOf(deepWork)
        // While running: no debrief yet
        assertNull(BubbleLogic.debriefWaiting(plan, 170 * MIN, emptySet(), emptySet()))
        // Right after end (180m): debrief waiting
        val d = BubbleLogic.debriefWaiting(plan, 181 * MIN, emptySet(), emptySet())
        assertEquals("Project coding: API endpoints", d?.title)
        // 25 min after end: still waiting
        assertEquals("Project coding: API endpoints", BubbleLogic.debriefWaiting(plan, 205 * MIN, emptySet(), emptySet())?.title)
        // 30 min after end: expired
        assertNull(BubbleLogic.debriefWaiting(plan, 210 * MIN, emptySet(), emptySet()))
    }

    @Test fun `debrief ignores anchors and trivial tasks`() {
        val plan = listOf(
            PlanBlock("Morning workout", 60 * MIN, 80 * MIN),
            PlanBlock("Quick task", 90 * MIN, 110 * MIN), // 20m task
        )
        assertNull(BubbleLogic.debriefWaiting(plan, 82 * MIN, emptySet(), emptySet()))
        assertNull(BubbleLogic.debriefWaiting(plan, 112 * MIN, emptySet(), emptySet()))
    }

    @Test fun `debrief respects answered and snoozed keys`() {
        val deepWork = PlanBlock("Study algorithm architecture", 60 * MIN, 150 * MIN)
        val plan = listOf(deepWork)
        val key = BubbleLogic.debriefKey(deepWork)
        assertEquals("Study algorithm architecture", BubbleLogic.debriefWaiting(plan, 155 * MIN, emptySet(), emptySet())?.title)
        assertNull(BubbleLogic.debriefWaiting(plan, 155 * MIN, setOf(key), emptySet()))
        assertNull(BubbleLogic.debriefWaiting(plan, 155 * MIN, emptySet(), setOf(key)))
    }

    @Test fun `blue ring triggers for task check or debrief`() {
        val tc = PlanBlock("Call dentist", 50 * MIN, 70 * MIN)
        val dw = PlanBlock("Deep work: Backend", 0, 60 * MIN)
        assertEquals(Ring.BLUE, BubbleLogic.ring(false, null, tc, null, Ring.NONE))
        assertEquals(Ring.BLUE, BubbleLogic.ring(false, null, null, dw, Ring.AMBER))
    }
}

private class FakeAnswers : AnswerDao {
    val rows = ArrayList<PhoneAnswer>()
    override suspend fun insert(answer: PhoneAnswer) { if (rows.none { it.id == answer.id }) rows += answer }
    override suspend fun pending(limit: Int) = rows.filter { it.state == PhoneAnswer.PENDING }.take(limit)
    override suspend fun markSynced(ids: List<String>) {
        for (i in rows.indices) if (rows[i].id in ids) rows[i] = rows[i].copy(state = PhoneAnswer.SYNCED)
    }
    override suspend fun prune(before: Long) { rows.removeAll { it.state == PhoneAnswer.SYNCED && it.atMs < before } }
}

class AnswerSyncTest {
    private val dao = FakeAnswers()
    private fun add(id: String) = runBlocking { dao.insert(PhoneAnswer(id, PhoneAnswer.TAP, 1000, "{}")) }

    @Test fun `answers are sent once and marked delivered`() = runBlocking {
        add("a"); add("b")
        val sent = ArrayList<String>()
        val out = AnswerSync(dao, object : AnswerRemote { override suspend fun uploadAnswers(batch: List<PhoneAnswer>) { sent += batch.map { it.id } } }).run()
        assertEquals(listOf("a", "b"), sent); assertEquals(2, out.sent); assertTrue(dao.pending(10).isEmpty())
    }

    @Test fun `a busy server keeps them for later`() = runBlocking {
        add("a")
        val out = AnswerSync(dao, object : AnswerRemote { override suspend fun uploadAnswers(batch: List<PhoneAnswer>) = throw TransientError("busy") }).run()
        assertTrue(out.retryLater); assertEquals(1, dao.pending(10).size)
    }

    @Test fun `a refusal is reported and not retried`() = runBlocking {
        add("a")
        val out = AnswerSync(dao, object : AnswerRemote { override suspend fun uploadAnswers(batch: List<PhoneAnswer>) = throw RejectedError(404, "no such function") }).run()
        assertFalse(out.retryLater); assertTrue(out.error!!.contains("404"))
    }

    @Test
    fun a_bedtime_before_five_is_after_midnight_of_the_next_date() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val day = java.time.LocalDate.of(2026, 11, 15)
        assertEquals("2026-11-15T20:30:00Z", BubbleLogic.intendedSleep(day, java.time.LocalTime.of(2, 0), zone).toString())   // 02:00 on the 16th
        assertEquals("2026-11-15T17:30:00Z", BubbleLogic.intendedSleep(day, java.time.LocalTime.of(23, 0), zone).toString())  // 23:00 on the 15th
    }
}
