package dev.elay.data.local

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Fresh in-memory Room database for host tests — no per-platform account file needed. */
fun testDatabase(): ElayDatabase =
    Room
        .inMemoryDatabaseBuilder<ElayDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
