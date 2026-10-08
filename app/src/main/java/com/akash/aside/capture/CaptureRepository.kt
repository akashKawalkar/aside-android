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
        true
    }

    /** After a crash or a killed app: whatever was typed is kept, whatever was empty is cleared. */
    suspend fun recoverOpen(): Int {
        var kept = 0
        for (item in dao.open()) if (commit(item.id, item.text, item.kind, item.dueAt)) kept++
        return kept
    }
}
