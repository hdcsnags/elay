package dev.elay.data.local

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Android database builder; per-account file (ADR-009). */
fun databaseBuilder(
    context: Context,
    accountKey: String,
): RoomDatabase.Builder<ElayDatabase> {
    val dbFile = context.getDatabasePath("elay-$accountKey.db")
    return Room
        .databaseBuilder<ElayDatabase>(context, dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
