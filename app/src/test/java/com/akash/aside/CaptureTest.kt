package com.akash.aside

import com.akash.aside.capture.CaptureRepository
import com.akash.aside.capture.Prefix
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
