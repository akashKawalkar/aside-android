package com.akash.aside.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object Kind {
    const val NOTE = "note"
    const val JOURNAL = "journal"
    const val TASK = "task"
}

object State {
    /** Being typed right now; never sent. */
    const val OPEN = "open"
    /** Finished, waiting to be sent. */
    const val COMMITTED = "committed"
    /** Delivered to the cloud. */
    const val SYNCED = "synced"
}

/** The id doubles as the client id the cloud uses to ignore duplicate sends. */
@Entity(tableName = "item", indices = [Index("state")])
data class Item(
    @PrimaryKey val id: String,
    val kind: String,
    val text: String,
    val dueAt: Long?,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Dao
interface ItemDao {
    @Upsert suspend fun upsert(item: Item)

    @Query("SELECT * FROM item WHERE id = :id") suspend fun get(id: String): Item?

    @Query("DELETE FROM item WHERE id = :id") suspend fun remove(id: String)

    @Query("SELECT * FROM item WHERE state = 'open' ORDER BY updatedAt DESC") suspend fun open(): List<Item>

    /** Finished items not yet delivered (including edits and deletions of delivered ones). */
    @Query("SELECT * FROM item WHERE state = 'committed' ORDER BY updatedAt") suspend fun committed(): List<Item>

    /** Marks delivered, but only if the item was not changed while it was being sent. */
    @Query("UPDATE item SET state = 'synced' WHERE id = :id AND updatedAt = :updatedAt AND state = 'committed'")
    suspend fun markSynced(id: String, updatedAt: Long)

    /** Forgets a deleted item once the cloud has been told, unless it changed meanwhile. */
    @Query("DELETE FROM item WHERE id = :id AND updatedAt = :updatedAt") suspend fun removeIfUnchanged(id: String, updatedAt: Long)

    /** Everything the user has finished writing and not deleted, newest first. */
    @Query("SELECT * FROM item WHERE state != 'open' AND deleted = 0 ORDER BY createdAt DESC")
    fun observeVisible(): Flow<List<Item>>

    /** How many finished items are still waiting to be sent. */
    @Query("SELECT COUNT(*) FROM item WHERE state = 'committed'") fun observePending(): Flow<Int>
}

@Database(entities = [Item::class, UsageSession::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun usageDao(): UsageDao
}

/** Version 2 added the usage table; nothing a person wrote is touched. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `usage_session` (`key` TEXT NOT NULL, `pkg` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_session_state` ON `usage_session` (`state`)")
    }
}
