package com.akash.aside.usage

import com.akash.aside.cloud.AuthRequired
import com.akash.aside.cloud.RejectedError
import com.akash.aside.cloud.TransientError
import com.akash.aside.data.UsageDao
import com.akash.aside.data.UsageSession

interface UsageRemote {
    suspend fun uploadUsage(batch: List<UsageSession>)
}

/** Sends stored sessions in batches, oldest first, and forgets old delivered ones. */
class UsageSync(private val dao: UsageDao, private val remote: UsageRemote, private val now: () -> Long = System::currentTimeMillis) {
    data class Outcome(val sent: Int, val retryLater: Boolean, val error: String?)

    suspend fun run(): Outcome {
        var sent = 0
        while (true) {
            val batch = dao.pending(BATCH)
            if (batch.isEmpty()) break
            try {
                remote.uploadUsage(batch)
            } catch (e: TransientError) {
                return Outcome(sent, true, e.message)
            } catch (e: AuthRequired) {
                return Outcome(sent, false, e.message)
            } catch (e: RejectedError) {
                return Outcome(sent, false, "The server refused usage data (${e.code}): ${e.message}")
            }
            dao.markSynced(batch.map { it.key })
            sent += batch.size
        }
        dao.prune(now() - KEEP_SENT_MS)
        return Outcome(sent, false, null)
    }

    companion object {
        const val BATCH = 500
        const val KEEP_SENT_MS = 7L * 24 * 60 * 60 * 1000
    }
}
