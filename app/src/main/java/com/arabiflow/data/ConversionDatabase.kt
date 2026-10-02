package com.arabiflow.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversions")
data class Conversion(
    @PrimaryKey val id: String,
    val sourcePath: String,
    val originalName: String,
    val appLabel: String = "",
    val signingCertificateSha256: String = "",
    val packageName: String,
    val version: String,
    val originalBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "queued",
    val stage: String = "بانتظار المعالجة",
    val progress: Int = 0,
    val outputPath: String? = null,
    val resultBytes: Long = 0,
    val elapsedSeconds: Double = 0.0,
    val report: String = "",
    val error: String = "",
    val workId: String? = null
)

@Dao
interface ConversionDao {
    @Query("SELECT * FROM conversions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Conversion>>

    @Query("SELECT * FROM conversions WHERE id=:id LIMIT 1")
    suspend fun get(id: String): Conversion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Conversion)

    @Query("UPDATE conversions SET status=:status, stage=:stage, progress=:progress WHERE id=:id")
    suspend fun updateStage(id: String, status: String, stage: String, progress: Int)

    @Query("UPDATE conversions SET status='completed', stage='مكتمل', progress=100, outputPath=:path, resultBytes=:size, elapsedSeconds=:seconds, report=:report WHERE id=:id")
    suspend fun finish(id: String, path: String, size: Long, seconds: Double, report: String)

    @Query("UPDATE conversions SET status='failed', stage='فشل التحويل', error=:message WHERE id=:id")
    suspend fun fail(id: String, message: String)

    @Query("UPDATE conversions SET status='cancelled', stage='ألغى المستخدم التحويل' WHERE id=:id")
    suspend fun cancel(id: String)

    @Query("UPDATE conversions SET workId=:workId WHERE id=:id")
    suspend fun linkWork(id: String, workId: String)

    @Query("DELETE FROM conversions WHERE id=:id")
    suspend fun delete(id: String)
}

@Database(entities = [Conversion::class], version = 1, exportSchema = true)
abstract class ConversionDatabase : RoomDatabase() {
    abstract fun dao(): ConversionDao
    companion object {
        @Volatile private var instance: ConversionDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                ConversionDatabase::class.java, "arabiflow_history.db").build().also { instance = it }
        }
    }
}
