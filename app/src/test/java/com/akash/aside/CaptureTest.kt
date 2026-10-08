package com.akash.aside

import com.akash.aside.capture.CaptureRepository
import com.akash.aside.capture.Prefix
import com.akash.aside.capture.Search
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.akash.aside.data.Item
import com.akash.aside.data.ItemDao
import com.akash.aside.data.Kind
import com.akash.aside.data.State
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDao : ItemDao {
    val rows = linkedMapOf<String, Item>()
    override suspend fun upsert(item: Item) { rows[item.id] = item }
    override suspend fun get(id: String) = rows[id]
    override suspend fun remove(id: String) { rows.remove(id) }
    override suspend fun open() = rows.values.filter { it.state == State.OPEN }
    override suspend fun committed() = rows.values.filter { it.state == State.COMMITTED }.sortedBy { it.updatedAt }
    override suspend fun markSynced(id: String, updatedAt: Long) {
        rows[id]?.let { if (it.updatedAt == updatedAt && it.state == State.COMMITTED) rows[id] = it.copy(state = State.SYNCED) }
    }
    override suspend fun removeIfUnchanged(id: String, updatedAt: Long) {
        rows[id]?.let { if (it.updatedAt == updatedAt) rows.remove(id) }
    }
    override fun observeVisible(): Flow<List<Item>> = flowOf(rows.values.filter { it.state != State.OPEN && !it.deleted })
    override fun observePending(): Flow<Int> = flowOf(rows.values.count { it.state == State.COMMITTED })
}

class SearchTest {
    @Test fun `empty search matches everything`() = assertTrue(Search.matchesAny("anything", "  "))

    @Test fun `any word is enough and case does not matter`() {
        assertTrue(Search.matchesAny("Call Rahul about tennis", "TENNIS dentist"))
        assertFalse(Search.matchesAny("Call Rahul about tennis", "dentist"))
    }
}

class EditDeleteTest {
    private val dao = FakeDao()
    private var clock = 1000L
    private val repo = CaptureRepository(dao) { clock++ }

    @Test fun `editing converts a note into a task with a due date`() = runBlocking {
        repo.commit("a", "call bank", Kind.NOTE, null)
        assertTrue(repo.edit("a", " call the bank ", Kind.TASK, 9000L))
        val item = dao.rows["a"]!!
        assertEquals(Kind.TASK, item.kind); assertEquals("call the bank", item.text); assertEquals(9000L, item.dueAt)
    }

    @Test fun `editing a sent item makes it unsent again`() = runBlocking {
        repo.commit("a", "idea", Kind.NOTE, null)
        dao.rows["a"] = dao.rows["a"]!!.copy(state = State.SYNCED)
        repo.edit("a", "better idea", Kind.NOTE, null)
        assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
    }

    @Test fun `turning a task back into a note drops the date`() = runBlocking {
        repo.commit("a", "x", Kind.TASK, 5000L)
        repo.edit("a", "x", Kind.NOTE, 5000L)
        assertNull(dao.rows["a"]!!.dueAt)
    }

    @Test fun `blank edits are refused`() = runBlocking {
        repo.commit("a", "keep me", Kind.NOTE, null)
        assertFalse(repo.edit("a", "  ", Kind.NOTE, null))
        assertEquals("keep me", dao.rows["a"]!!.text)
    }

    @Test fun `delete hides the item but keeps it to tell the cloud`() = runBlocking {
        repo.commit("a", "bye", Kind.NOTE, null)
        repo.delete("a")
        assertTrue(dao.rows["a"]!!.deleted)
        assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
    }
}

class PrefixTest {
    @Test fun `no prefix leaves the text alone`() {
        val r = Prefix.parse("buy milk")
        assertNull(r.kind); assertEquals("buy milk", r.text)
    }

    @Test fun `short and long prefixes pick the type and are stripped`() {
        assertEquals(Kind.JOURNAL, Prefix.parse("j: tired today").kind)
        assertEquals("tired today", Prefix.parse("J:   tired today").text)
        assertEquals(Kind.TASK, Prefix.parse("task: call bank").kind)
        assertEquals("call bank", Prefix.parse("t:call bank").text)
        assertEquals(Kind.NOTE, Prefix.parse("note: idea").kind)
    }

    @Test fun `a colon later in the text is not a prefix`() {
        assertNull(Prefix.parse("meeting at 5: bring laptop").kind)
        assertNull(Prefix.parse("tomorrow: gym").kind)
    }
}

class CaptureRepositoryTest {
    private val dao = FakeDao()
    private var clock = 1000L
    private val repo = CaptureRepository(dao) { clock++ }

    @Test fun `typing saves an open draft`() = runBlocking {
        repo.saveDraft("a", "hello", Kind.NOTE, null)
        assertEquals(State.OPEN, dao.rows["a"]!!.state)
    }

    @Test fun `commit keeps text and strips a prefix`() = runBlocking {
        repo.saveDraft("a", "t: call bank", Kind.NOTE, 5000L)
        assertTrue(repo.commit("a", "t: call bank", Kind.NOTE, 5000L))
        val item = dao.rows["a"]!!
        assertEquals(Kind.TASK, item.kind); assertEquals("call bank", item.text)
        assertEquals(5000L, item.dueAt); assertEquals(State.COMMITTED, item.state)
    }

    @Test fun `a due date is dropped for notes`() = runBlocking {
        repo.commit("a", "idea", Kind.NOTE, 5000L)
        assertNull(dao.rows["a"]!!.dueAt)
    }

    @Test fun `blank text is discarded`() = runBlocking {
        repo.saveDraft("a", "x", Kind.NOTE, null)
        assertFalse(repo.commit("a", "   ", Kind.NOTE, null))
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `only a prefix is blank`() = runBlocking {
        assertFalse(repo.commit("a", "t:  ", Kind.NOTE, null))
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `a late draft save cannot reopen a committed item`() = runBlocking {
        repo.commit("a", "done", Kind.NOTE, null)
        repo.saveDraft("a", "done and more", Kind.NOTE, null)
        assertEquals("done", dao.rows["a"]!!.text)
        assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
    }

    @Test fun `recovery commits typed text and clears empty drafts`() = runBlocking {
        repo.saveDraft("a", "j: half a thought", Kind.NOTE, null)
        dao.rows["b"] = Item("b", Kind.NOTE, "  ", null, State.OPEN, 1, 1)
        assertEquals(1, repo.recoverOpen())
        assertEquals(Kind.JOURNAL, dao.rows["a"]!!.kind)
        assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
        assertNull(dao.rows["b"])
    }
}

private class FakeRemote(var failWith: Exception? = null, var onCapture: suspend (Item) -> Unit = {}) : com.akash.aside.cloud.Remote {
    val captured = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    override suspend fun capture(item: Item) {
        onCapture(item)
        failWith?.let { throw it }
        captured += item.id
    }
    override suspend fun delete(id: String) { failWith?.let { throw it }; deleted += id }
}

class SyncEngineTest {
    private val dao = FakeDao()
    private var clock = 1000L
    private val repo = CaptureRepository(dao) { clock++ }

    @Test fun `sends what is waiting and marks it delivered`() = runBlocking {
        repo.commit("a", "one", Kind.NOTE, null); repo.commit("b", "two", Kind.TASK, 5L)
        val remote = FakeRemote()
        val out = com.akash.aside.cloud.SyncEngine(dao, remote).run()
        assertEquals(listOf("a", "b"), remote.captured); assertEquals(2, out.sent)
        assertTrue(dao.rows.values.all { it.state == State.SYNCED })
        assertEquals(0, com.akash.aside.cloud.SyncEngine(dao, remote).run().sent)         // nothing left to send
    }

    @Test fun `a network failure stops the run, keeps everything waiting and asks for a retry`() = runBlocking {
        repo.commit("a", "one", Kind.NOTE, null)
        val out = com.akash.aside.cloud.SyncEngine(dao, FakeRemote(com.akash.aside.cloud.TransientError("offline"))).run()
        assertTrue(out.retryLater); assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
    }

    @Test fun `a refused item does not block the others`() = runBlocking {
        repo.commit("a", "bad", Kind.NOTE, null); repo.commit("b", "good", Kind.NOTE, null)
        val remote = FakeRemote(onCapture = { if (it.id == "a") throw com.akash.aside.cloud.RejectedError(400, "nope") })
        val out = com.akash.aside.cloud.SyncEngine(dao, remote).run()
        assertEquals(1, out.rejected); assertEquals(listOf("b"), remote.captured)
        assertEquals(State.COMMITTED, dao.rows["a"]!!.state); assertEquals(State.SYNCED, dao.rows["b"]!!.state)
        assertFalse(out.retryLater)
    }

    @Test fun `an edit made while sending stays waiting`() = runBlocking {
        repo.commit("a", "first", Kind.NOTE, null)
        val remote = FakeRemote(onCapture = { repo.edit("a", "second", Kind.NOTE, null) })
        com.akash.aside.cloud.SyncEngine(dao, remote).run()
        assertEquals("second", dao.rows["a"]!!.text); assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
    }

    @Test fun `deleted items are removed from the cloud and then forgotten`() = runBlocking {
        repo.commit("a", "bye", Kind.NOTE, null); repo.delete("a")
        val remote = FakeRemote()
        com.akash.aside.cloud.SyncEngine(dao, remote).run()
        assertEquals(listOf("a"), remote.deleted); assertNull(dao.rows["a"])
    }

    @Test fun `signed out stops without retrying`() = runBlocking {
        repo.commit("a", "x", Kind.NOTE, null)
        val out = com.akash.aside.cloud.SyncEngine(dao, FakeRemote(com.akash.aside.cloud.AuthRequired())).run()
        assertFalse(out.retryLater); assertEquals(State.COMMITTED, dao.rows["a"]!!.state)
    }
}
