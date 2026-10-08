package com.akash.aside.cloud

import com.akash.aside.data.Item
import com.akash.aside.data.ItemDao

/** The cloud, as far as the sender is concerned. */
interface Remote {
    suspend fun capture(item: Item)
    suspend fun delete(id: String)
}

/** No connection, a timeout, or the server having a bad moment: try again later. */
class TransientError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server understood and said no (bad data). Retrying the same thing will not help. */
class RejectedError(val code: Int, message: String) : Exception(message)

/** Not signed in, or the sign-in has expired for good. */
class AuthRequired(message: String = "Sign in again") : Exception(message)

/**
 * Sends everything waiting, oldest first. Every item has its own id, so sending the same one twice is harmless.
 * An item that was edited while it was in flight stays waiting and goes out again.
 */
class SyncEngine(private val dao: ItemDao, private val remote: Remote) {
    data class Outcome(val sent: Int, val rejected: Int, val retryLater: Boolean, val error: String?)

    suspend fun run(): Outcome {
        var sent = 0
        var rejected = 0
        var error: String? = null
        for (item in dao.committed()) {
            try {
                if (item.deleted) {
                    remote.delete(item.id)
                    dao.removeIfUnchanged(item.id, item.updatedAt)
                } else {
                    remote.capture(item)
                    dao.markSynced(item.id, item.updatedAt)
                }
                sent++
            } catch (e: TransientError) {
                return Outcome(sent, rejected, retryLater = true, error = e.message)
            } catch (e: AuthRequired) {
                return Outcome(sent, rejected, retryLater = false, error = e.message)
            } catch (e: RejectedError) {
                rejected++
                error = "The server refused an item (${e.code}): ${e.message}"
            }
        }
        return Outcome(sent, rejected, retryLater = false, error = error)
    }
}
