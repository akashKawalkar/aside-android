package com.akash.aside.capture

import com.akash.aside.data.Item
import com.akash.aside.data.ItemDao
import com.akash.aside.data.Kind
import com.akash.aside.data.State
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The capture rules, free of Android so they can be tested on the JVM.
 * Text is saved as it is typed (state open) and becomes committed when the window closes; nothing is ever asked.
 */
class CaptureRepository(
    private val dao: ItemDao,
    private val now: () -> Long = System::currentTimeMillis,
    /** Told after every committed change, so the app can start sending. */
    private val onChanged: () -> Unit = {},
) {
    private val lock = Mutex()

    fun newId(): String = UUID.randomUUID().toString()

    /** Called on every pause in typing. Never touches an item that is already committed. */
    suspend fun saveDraft(id: String, raw: String, chip: String, dueAt: Long?) = lock.withLock {
        val existing = dao.get(id)
        if (existing != null && existing.state != State.OPEN) return@withLock
        if (raw.isBlank()) {
            if (existing != null) dao.remove(id)
            return@withLock
        }
        val t = now()
        dao.upsert(Item(id, chip, raw, dueAt, State.OPEN, existing?.createdAt ?: t, t))
    }

    /** Returns true if something was kept. Blank text is dropped; the type comes from a prefix if there is one. */
    suspend fun commit(id: String, raw: String, chip: String, dueAt: Long?): Boolean = lock.withLock {
        val existing = dao.get(id)
        if (existing != null && existing.state != State.OPEN) return@withLock true
        val parsed = Prefix.parse(raw)
        val text = parsed.text.trim()
        if (text.isEmpty()) {
            if (existing != null) dao.remove(id)
            return@withLock false
        }
        val kind = parsed.kind ?: chip
        val t = now()
        dao.upsert(
            Item(
                id, kind, text, if (kind == Kind.TASK) dueAt else null,
                State.COMMITTED, existing?.createdAt ?: t, t,
            )
        )
        onChanged()
        true
    }

    fun observeVisible() = dao.observeVisible()

    fun observePending() = dao.observePending()

    /** An edit made inside the app. A sent item becomes unsent again so the change goes out. Blank text is refused. */
    suspend fun edit(id: String, raw: String, kind: String, dueAt: Long?): Boolean = lock.withLock {
        val existing = dao.get(id) ?: return@withLock false
        val text = raw.trim()
        if (text.isEmpty()) return@withLock false
        dao.upsert(
            existing.copy(
                kind = kind, text = text, dueAt = if (kind == Kind.TASK) dueAt else null,
                state = State.COMMITTED, updatedAt = now(),
            )
        )
        onChanged()
        true
    }

    /** Hidden at once; kept until the cloud has been told, so a sent item can be removed there too. */
    suspend fun delete(id: String) = lock.withLock {
        val existing = dao.get(id) ?: return@withLock
        dao.upsert(existing.copy(deleted = true, state = State.COMMITTED, updatedAt = now()))
        onChanged()
    }

    /** After a crash or a killed app: whatever was typed is kept, whatever was empty is cleared. */
    suspend fun recoverOpen(): Int {
        var kept = 0
        for (item in dao.open()) if (commit(item.id, item.text, item.kind, item.dueAt)) kept++
        return kept
    }
}
