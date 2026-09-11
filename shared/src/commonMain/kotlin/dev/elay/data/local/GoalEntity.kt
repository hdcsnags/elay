package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Mirrors contract §1 `goals` (columns needed to reconstruct the frozen [dev.elay.domain.model.Goal]). */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val title: String,
    val notes: String?,
    val targetDate: String?,
    val status: String,
    val version: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val syncStatus: String,
    val localUpdatedAtEpochMs: Long,
)

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY title")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun get(id: String): GoalEntity?

    @Upsert
    suspend fun upsert(entity: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: String)
}
