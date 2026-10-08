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

    /** Everything the user has finished writing and not deleted, newest first. */
    @Query("SELECT * FROM item WHERE state != 'open' AND deleted = 0 ORDER BY createdAt DESC")
    fun observeVisible(): Flow<List<Item>>

    /** How many finished items are still waiting to be sent. */
    @Query("SELECT COUNT(*) FROM item WHERE state = 'committed'") fun observePending(): Flow<Int>
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun itemDao(): ItemDao
}
