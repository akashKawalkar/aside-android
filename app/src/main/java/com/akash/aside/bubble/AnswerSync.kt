package com.akash.aside.bubble

import com.akash.aside.cloud.AuthRequired
import com.akash.aside.cloud.RejectedError
import com.akash.aside.cloud.TransientError
import com.akash.aside.data.AnswerDao

/** Sends stored answers in batches, oldest first, and forgets old delivered ones. */
class AnswerSync(private val dao: AnswerDao, private val remote: AnswerRemote, private val now: () -> Long = System::currentTimeMillis) {
    data class Outcome(val sent: Int, val retryLater: Boolean, val error: String?)

    suspend fun run(): Outcome {
        var sent = 0
        while (true) {
            val batch = dao.pending(BATCH)
            if (batch.isEmpty()) break
            try {
                remote.uploadAnswers(batch)
            } catch (e: TransientError) {
                return Outcome(sent, true, e.message)
            } catch (e: AuthRequired) {
                return Outcome(sent, false, e.message)
            } catch (e: RejectedError) {
                return Outcome(sent, false, "The server refused answers (${e.code}): ${e.message}")
            }
            dao.markSynced(batch.map { it.id })
            sent += batch.size
        }
        dao.prune(now() - KEEP_SENT_MS)
        return Outcome(sent, false, null)
    }

    companion object {
        const val BATCH = 200
        const val KEEP_SENT_MS = 7L * 24 * 60 * 60 * 1000
    }
}
