package com.fallbracelet.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One event received from the bracelet. (deviceAddress, seq) is unique so resends are dropped. */
@Entity(tableName = "events", indices = [Index(value = ["deviceAddress", "seq"], unique = true)])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val seq: Long,
    /** [com.fallbracelet.ble.EventType] name. */
    val type: String,
    val batteryPct: Int,
    /** When it happened on the bracelet, epoch ms. */
    val occurredAt: Long,
    val receivedAt: Long,
    val impactG: Float,
    val alarms24h: Int,
    val cancelled24h: Int,
    val message: String,
    /** Null when no SMS was attempted. */
    val smsSent: Boolean? = null,
)

@Dao
interface EventDao {
    /** Returns -1 when the event was already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("UPDATE events SET smsSent = :sent WHERE id = :id")
    suspend fun setSmsSent(id: Long, sent: Boolean)

    @Query("SELECT * FROM events ORDER BY occurredAt DESC, id DESC LIMIT 500")
    fun recent(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE type = 'CHECKIN' ORDER BY receivedAt DESC LIMIT 1")
    suspend fun lastCheckin(): EventEntity?

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bracelet.db").build()
    }
}
