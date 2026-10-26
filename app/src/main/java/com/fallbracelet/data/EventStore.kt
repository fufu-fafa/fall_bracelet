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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** When the relay accepted it, epoch ms; null while it waits in the upload queue. */
    val uploadedAt: Long? = null,
)

@Dao
interface EventDao : UploadQueue {
    /** Returns -1 when the event was already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE uploadedAt IS NULL ORDER BY occurredAt ASC, id ASC")
    override suspend fun pendingUploads(): List<EventEntity>

    @Query("UPDATE events SET uploadedAt = :at WHERE id = :id")
    override suspend fun markUploaded(id: Long, at: Long)

    @Query("SELECT * FROM events ORDER BY occurredAt DESC, id DESC LIMIT 500")
    fun recent(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE type = 'CHECKIN' ORDER BY occurredAt DESC LIMIT 1")
    fun lastCheckin(): Flow<EventEntity?>

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Database(entities = [EventEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bracelet.db")
                .addMigrations(SMS_TO_UPLOAD_QUEUE)
                .build()

        /**
         * v2 drops smsSent (SMS is removed) and adds uploadedAt. Events stored before the relay
         * existed are marked uploaded, so they never reach caregivers as fresh alarms.
         */
        val SMS_TO_UPLOAD_QUEUE = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE events_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "deviceAddress TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, " +
                        "batteryPct INTEGER NOT NULL, occurredAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL, " +
                        "impactG REAL NOT NULL, alarms24h INTEGER NOT NULL, cancelled24h INTEGER NOT NULL, " +
                        "message TEXT NOT NULL, uploadedAt INTEGER)"
                )
                db.execSQL(
                    "INSERT INTO events_new (id, deviceAddress, seq, type, batteryPct, occurredAt, receivedAt, " +
                        "impactG, alarms24h, cancelled24h, message, uploadedAt) " +
                        "SELECT id, deviceAddress, seq, type, batteryPct, occurredAt, receivedAt, " +
                        "impactG, alarms24h, cancelled24h, message, receivedAt FROM events"
                )
                db.execSQL("DROP TABLE events")
                db.execSQL("ALTER TABLE events_new RENAME TO events")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_events_deviceAddress_seq ON events (deviceAddress, seq)")
            }
        }
    }
}
