package com.akash.aside

import com.akash.aside.cloud.TransientError
import com.akash.aside.data.UsageDao
import com.akash.aside.data.UsageSession
import com.akash.aside.usage.RawEvent
import com.akash.aside.usage.RawEvent.Type.PAUSE
import com.akash.aside.usage.RawEvent.Type.RESUME
import com.akash.aside.usage.RawEvent.Type.SCREEN_OFF
import com.akash.aside.usage.SessionBuilder
import com.akash.aside.usage.UsageRemote
import com.akash.aside.usage.UsageSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = 1_000L
private fun ev(t: Long, type: RawEvent.Type, pkg: String) = RawEvent(t * S, type, pkg)

class SessionBuilderTest {
    private val b = SessionBuilder(ignored = { it == "ignored.app" || it == "launcher" })

    @Test fun `one app resumed and paused is one session`() {
        val r = b.build(listOf(ev(100, RESUME, "yt"), ev(400, PAUSE, "yt")))
        assertEquals(listOf(SessionBuilder.Span("yt", 100 * S, 400 * S)), r.closed)
        assertNull(r.openSince)
    }

    @Test fun `switching apps ends the first and starts the next, even if the pause comes late`() {
        val r = b.build(listOf(ev(100, RESUME, "a"), ev(200, RESUME, "b"), ev(201, PAUSE, "a"), ev(300, PAUSE, "b")))
        assertEquals(listOf("a" to (100L to 200L), "b" to (200L to 300L)), r.closed.map { it.pkg to (it.startMs / S to it.endMs / S) })
    }

    @Test fun `the app still in front is not a session yet but is reported`() {
        val r = b.build(listOf(ev(100, RESUME, "a"), ev(200, PAUSE, "a"), ev(250, RESUME, "b")))
        assertEquals(1, r.closed.size); assertEquals(250 * S, r.openSince)
    }

    @Test fun `screen off closes the session`() {
        val r = b.build(listOf(ev(100, RESUME, "a"), ev(160, SCREEN_OFF, "")))
        assertEquals(60 * S, r.closed.single().endMs - r.closed.single().startMs)
    }

    @Test fun `ignored apps and the launcher are never counted and cut the previous session`() {
        val r = b.build(listOf(ev(100, RESUME, "a"), ev(150, RESUME, "ignored.app"), ev(300, PAUSE, "ignored.app"), ev(310, RESUME, "launcher"), ev(320, PAUSE, "launcher")))
        assertEquals(listOf("a"), r.closed.map { it.pkg }); assertEquals(150 * S, r.closed.single().endMs)
    }

    @Test fun `blips under three seconds are dropped`() {
        assertTrue(b.build(listOf(ev(100, RESUME, "a"), ev(102, PAUSE, "a"))).closed.isEmpty())
    }

    @Test fun `a quick hop between two screens of the same app is one session`() {
        val r = b.build(listOf(ev(100, RESUME, "a"), ev(200, PAUSE, "a"), ev(201, RESUME, "a"), ev(300, PAUSE, "a")))
        assertEquals(1, r.closed.size); assertEquals(300 * S, r.closed.single().endMs)
    }

    @Test fun `a second pause for the same app changes nothing`() {
        val r = b.build(listOf(ev(100, RESUME, "a"), ev(200, PAUSE, "a"), ev(205, PAUSE, "a")))
        assertEquals(1, r.closed.size)
    }

    @Test fun `the same session always gets the same key, a different one a different key`() {
        assertEquals(SessionBuilder.key("a", 5), SessionBuilder.key("a", 5))
        assertNotEquals(SessionBuilder.key("a", 5), SessionBuilder.key("a", 6))
        assertNotEquals(SessionBuilder.key("a", 5), SessionBuilder.key("b", 5))
        assertTrue(SessionBuilder.key("a", 5).length <= 56)
    }
}

private class FakeUsageDao : UsageDao {
    val rows = linkedMapOf<String, UsageSession>()
    override suspend fun insert(sessions: List<UsageSession>) { sessions.forEach { rows.putIfAbsent(it.key, it) } }
    override suspend fun pending(limit: Int) = rows.values.filter { it.state == UsageSession.PENDING }.sortedBy { it.startMs }.take(limit)
    override suspend fun markSynced(keys: List<String>) { keys.forEach { k -> rows[k]?.let { rows[k] = it.copy(state = UsageSession.SYNCED) } } }
    override suspend fun prune(before: Long) { rows.entries.removeIf { it.value.state == UsageSession.SYNCED && it.value.endMs < before } }
    override fun observePending(): Flow<Int> = flowOf(rows.values.count { it.state == UsageSession.PENDING })
}

class UsageSyncTest {
    private val dao = FakeUsageDao()
    private fun session(i: Int) = UsageSession("k$i", "a", i * 10L, i * 10L + 5, UsageSession.PENDING)

    @Test fun `sends in batches and marks them delivered`() = runBlocking {
        dao.insert((1..1100).map(::session))
        val sizes = mutableListOf<Int>()
        val out = UsageSync(dao, object : UsageRemote { override suspend fun uploadUsage(batch: List<UsageSession>) { sizes += batch.size } }).run()
        assertEquals(listOf(500, 500, 100), sizes); assertEquals(1100, out.sent)
        assertTrue(dao.rows.values.all { it.state == UsageSession.SYNCED })
    }

    @Test fun `a failed send keeps the batch for next time`() = runBlocking {
        dao.insert(listOf(session(1)))
        val out = UsageSync(dao, object : UsageRemote { override suspend fun uploadUsage(batch: List<UsageSession>) { throw TransientError("offline") } }).run()
        assertTrue(out.retryLater); assertEquals(UsageSession.PENDING, dao.rows["k1"]!!.state)
    }

    @Test fun `storing the same session twice keeps one`() = runBlocking {
        dao.insert(listOf(session(1), session(1)))
        assertEquals(1, dao.rows.size)
    }

    @Test fun `old delivered sessions are forgotten, recent ones kept`() = runBlocking {
        val now = 100L * 24 * 60 * 60 * 1000
        dao.rows["old"] = UsageSession("old", "a", 0, 1000, UsageSession.SYNCED)
        dao.rows["new"] = UsageSession("new", "a", now - 1000, now, UsageSession.SYNCED)
        UsageSync(dao, object : UsageRemote { override suspend fun uploadUsage(batch: List<UsageSession>) {} }, { now }).run()
        assertFalse("old" in dao.rows); assertTrue("new" in dao.rows)
    }
}
