package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "offline_receipt_drafts")
data class OfflineDraftEntity(
    @PrimaryKey val draftId: String,
    val businessId: String,
    val ownerUid: String,
    val customerName: String,
    val customerPhone: String,
    val customerEmail: String,
    val customerAddress: String,
    val paymentPurpose: String,
    val invoiceNumber: String,
    val orderNumber: String,
    val notes: String,
    val amount: Double,
    val currency: String,
    val senderName: String,
    val senderBank: String,
    val beneficiaryBank: String,
    val transactionReference: String,
    val sessionId: String,
    val transactionDate: String,
    val transactionTime: String,
    val transferChannel: String,
    val paymentNarration: String,
    val documentLocalUri: String,
    val documentName: String,
    val templateType: String,
    val syncStatus: String, // DRAFT, PENDING_SYNC, SYNCED
    val updatedAtMillis: Long
)

@Dao
interface OfflineDraftDao {
    @Query("SELECT * FROM offline_receipt_drafts WHERE businessId = :businessId ORDER BY updatedAtMillis DESC")
    fun observeDrafts(businessId: String): Flow<List<OfflineDraftEntity>>

    @Query("SELECT * FROM offline_receipt_drafts WHERE businessId = :businessId AND syncStatus != 'SYNCED' ORDER BY updatedAtMillis ASC")
    suspend fun getPendingDrafts(businessId: String): List<OfflineDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: OfflineDraftEntity)

    @Query("DELETE FROM offline_receipt_drafts WHERE draftId = :draftId")
    suspend fun deleteDraft(draftId: String)
}

@Database(entities = [OfflineDraftEntity::class], version = 1, exportSchema = false)
abstract class AppLocalDatabase : RoomDatabase() {
    abstract fun offlineDraftDao(): OfflineDraftDao

    companion object {
        @Volatile
        private var INSTANCE: AppLocalDatabase? = null

        fun getInstance(context: Context): AppLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppLocalDatabase::class.java,
                    "receipts_1803_offline.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
