package dev.elay.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS database builder. Creates the Application Support subdirectory first —
 * opening SQLite in a missing directory fails with SQLITE_CANTOPEN (council
 * finding, 2026-09-11). Database file is per-account (ADR-009): pass a
 * stable, non-sensitive account key.
 */
@OptIn(ExperimentalForeignApi::class)
fun databaseBuilder(accountKey: String): RoomDatabase.Builder<ElayDatabase> {
    val fileManager = NSFileManager.defaultManager
    val appSupport =
        fileManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) as NSURL
    val dir = appSupport.URLByAppendingPathComponent("elay")!!
    fileManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val dbPath = dir.URLByAppendingPathComponent("elay-$accountKey.db")!!.path!!
    return Room
        .databaseBuilder<ElayDatabase>(name = dbPath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
