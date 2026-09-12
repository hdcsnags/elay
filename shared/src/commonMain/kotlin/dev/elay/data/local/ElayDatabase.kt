package dev.elay.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(
    entities = [
        OutboxEntity::class,
        GoalEntity::class,
        MilestoneEntity::class,
        TaskEntity::class,
        CaptureEntity::class,
        TimeBlockEntity::class,
    ],
    version = 1,
)
@ConstructedBy(ElayDatabaseConstructor::class)
abstract class ElayDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    abstract fun goalDao(): GoalDao

    abstract fun milestoneDao(): MilestoneDao

    abstract fun taskDao(): TaskDao

    abstract fun captureDao(): CaptureDao

    abstract fun timeBlockDao(): TimeBlockDao

    abstract fun plannerWriteDao(): PlannerWriteDao

    // Additive accessor (seat R grant, contracts/phase1-planner.md §5): backs
    // dev.elay.data.repository.LocalFirstPlannerRepository's optimistic staging writes.
    abstract fun plannerStagingDao(): PlannerStagingDao
}

// Room's KSP generates the actual implementations per platform.
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ElayDatabaseConstructor : RoomDatabaseConstructor<ElayDatabase> {
    override fun initialize(): ElayDatabase
}
