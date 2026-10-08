package com.akash.aside.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One stretch of time a single app was in the foreground. The key is made from the app and the start time, so the same session can never be stored or sent twice. */
@Entity(tableName = "usage_session", indices = [Index("state")])
data class UsageSession(
    @PrimaryKey val key: String,
    val pkg: String,
    val startMs: Long,
    val endMs: Long,
    val state: String,
) {
    companion object {
        const val PENDING = "pending"
        const val SYNCED = "synced"
    }
}

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(sessions: List<UsageSession>)

    @Query("SELECT * FROM usage_session WHERE state = 'pending' ORDER BY startMs LIMIT :limit")
    suspend fun pending(limit: Int): List<UsageSession>

    @Query("UPDATE usage_session SET state = 'synced' WHERE `key` IN (:keys)") suspend fun markSynced(keys: List<String>)

    @Query("DELETE FROM usage_session WHERE state = 'synced' AND endMs < :before") suspend fun prune(before: Long)

    @Query("SELECT COUNT(*) FROM usage_session WHERE state = 'pending'") fun observePending(): Flow<Int>
}
