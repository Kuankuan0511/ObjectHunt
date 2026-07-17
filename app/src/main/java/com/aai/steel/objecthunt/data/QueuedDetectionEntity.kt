package com.aai.steel.objecthunt.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Queued detection request when network is down.
 * Stores image + city + retry metadata for later sync.
 */
@Entity(tableName = "queued_detections")
data class QueuedDetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val city: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val imageBytes: ByteArray,
    val retryCount: Int = 0,
    val nextRetryAt: Long = System.currentTimeMillis(), // timestamp when to retry next
    val status: String = "PENDING" // PENDING, RETRYING, FAILED
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QueuedDetectionEntity
        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (city != other.city) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (retryCount != other.retryCount) return false
        if (nextRetryAt != other.nextRetryAt) return false
        if (status != other.status) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (city?.hashCode() ?: 0)
        result = 31 * result + imageBytes.contentHashCode()
        result = 31 * result + retryCount
        result = 31 * result + nextRetryAt.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}
