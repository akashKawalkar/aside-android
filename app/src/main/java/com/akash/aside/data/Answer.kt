package com.akash.aside.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Something the user answered or tapped: a distraction question, the sleep or evening sheet, the anomaly question,
 * or a receptivity tap. Stored here first and uploaded like usage. The id doubles as the cloud's duplicate guard.
 */
@Entity(tableName = "phone_answer", indices = [Index("state")])
data class PhoneAnswer(
    @PrimaryKey val id: String,
    val kind: String,
    val atMs: Long,
    /** A JSON object, as text. */
    val payload: String,
    val state: String = PENDING,
) {
    companion object {
        const val PENDING = "pending"
        const val SYNCED = "synced"

        const val DISTRACTION = "distraction"
        const val SLEEP = "sleep"
        const val EVENING = "evening"
        const val ANOMALY = "anomaly"
        const val TAP = "tap"
    }
}

@Dao
interface AnswerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(answer: PhoneAnswer)

    @Query("SELECT * FROM phone_answer WHERE state = 'pending' ORDER BY atMs LIMIT :limit")
    suspend fun pending(limit: Int): List<PhoneAnswer>

    @Query("UPDATE phone_answer SET state = 'synced' WHERE id IN (:ids)") suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM phone_answer WHERE state = 'synced' AND atMs < :before") suspend fun prune(before: Long)
}

/** Version 3 added the answers table; nothing else is touched. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `phone_answer` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `payload` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_phone_answer_state` ON `phone_answer` (`state`)")
    }
}
