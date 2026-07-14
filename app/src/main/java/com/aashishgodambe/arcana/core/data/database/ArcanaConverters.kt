package com.aashishgodambe.arcana.core.data.database

import androidx.room.TypeConverter
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleOrigin
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters. Enums are stored by name; LocalDate as epoch-day; Instant as epoch-milli.
 * Converters are non-null — Room generates the null guards for nullable columns.
 */
class ArcanaConverters {
    @TypeConverter fun fromLocalDate(value: LocalDate): Long = value.toEpochDay()
    @TypeConverter fun toLocalDate(value: Long): LocalDate = LocalDate.ofEpochDay(value)

    @TypeConverter fun fromInstant(value: Instant): Long = value.toEpochMilli()
    @TypeConverter fun toInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    @TypeConverter fun fromCategory(value: CollectibleCategory): String = value.name
    @TypeConverter fun toCategory(value: String): CollectibleCategory = CollectibleCategory.valueOf(value)

    @TypeConverter fun fromOrigin(value: CollectibleOrigin): String = value.name
    @TypeConverter fun toOrigin(value: String): CollectibleOrigin = CollectibleOrigin.valueOf(value)

    @TypeConverter fun fromValueSource(value: ValueSource): String = value.name
    @TypeConverter fun toValueSource(value: String): ValueSource = ValueSource.valueOf(value)

    @TypeConverter fun fromSnapshotTrigger(value: SnapshotTrigger): String = value.name
    @TypeConverter fun toSnapshotTrigger(value: String): SnapshotTrigger = SnapshotTrigger.valueOf(value)

    // Embedding vectors, stored as a little-endian float BLOB (the RAG vector store).
    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(value.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(value)
        return buf.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray): FloatArray {
        val floats = FloatArray(value.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }
}
