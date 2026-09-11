package dev.elay.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(entities = [OutboxEntity::class], version = 1)
@ConstructedBy(ElayDatabaseConstructor::class)
abstract class ElayDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
}

// Room's KSP generates the actual implementations per platform.
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ElayDatabaseConstructor : RoomDatabaseConstructor<ElayDatabase> {
    override fun initialize(): ElayDatabase
}
