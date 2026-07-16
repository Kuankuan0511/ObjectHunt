package com.aai.steel.objecthunt.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for saved pigeon hunts.
 * Stores all info: photo, pigeon type, confidence, features, city, etc.
 * Max 20 rows enforced by DAO insertWithLimit().
 */
@Entity(tableName = "saved_pigeons")
data class PigeonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val pigeonType: String?,
    val confidence: Float,
    val features: String?,
    /** Where pigeon was in image (e.g. "center", "top-left") - from Muse */
    val pigeonLocationInImage: String?,
    /** City where photo was taken (e.g. "San Francisco") - from Geocoder */
    val city: String?,
    val description: String,
    val rawResponse: String,
    /** JPEG bytes, 1024px max, 80% quality (~100-300KB) */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val imageBytes: ByteArray
) {
    // Room needs equals/hashCode for ByteArray - data class default uses reference, so override
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PigeonEntity

        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (pigeonType != other.pigeonType) return false
        if (confidence != other.confidence) return false
        if (features != other.features) return false
        if (pigeonLocationInImage != other.pigeonLocationInImage) return false
        if (city != other.city) return false
        if (description != other.description) return false
        if (rawResponse != other.rawResponse) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (pigeonType?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (features?.hashCode() ?: 0)
        result = 31 * result + (pigeonLocationInImage?.hashCode() ?: 0)
        result = 31 * result + (city?.hashCode() ?: 0)
        result = 31 * result + description.hashCode()
        result = 31 * result + rawResponse.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        return result
    }
}
