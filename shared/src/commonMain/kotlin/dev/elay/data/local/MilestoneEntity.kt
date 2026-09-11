package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Mirrors contract §1 `milestones` (lean child of goals — no owner/visibility of its own). */
@Entity(tableName = "milestones")
data class MilestoneEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val title: String,
    val targetDate: String?,
    val sortOrder: Int,
    val status: String,
    val version: Long,
    val syncStatus: String,
    val localUpdatedAtEpochMs: Long,
)

@Dao
interface MilestoneDao {
    @Query("SELECT * FROM milestones WHERE goalId = :goalId ORDER BY sortOrder")
    fun observeForGoal(goalId: String): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones WHERE id = :id")
    suspend fun get(id: String): MilestoneEntity?

    @Upsert
    suspend fun upsert(entity: MilestoneEntity)

    @Query("DELETE FROM milestones WHERE id = :id")
    suspend fun delete(id: String)
}
