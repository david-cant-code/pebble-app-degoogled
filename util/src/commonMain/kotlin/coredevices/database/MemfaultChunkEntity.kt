package coredevices.database

import androidx.room.Entity
import androidx.room.PrimaryKey

// Unused: nothing reads or writes this table. It stays in CoreDatabase because removing it
// changes the schema and needs a migration.
@Entity(tableName = "memfault_chunks")
data class MemfaultChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serial: String,
    val chunkData: ByteArray,
    val createdAt: Long,
)
