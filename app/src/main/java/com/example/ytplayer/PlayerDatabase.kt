package com.example.ytplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PlayerDatabase(context: Context) :
    SQLiteOpenHelper(context, "ytplayer.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE favorites (
                url TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                thumbnail_url TEXT,
                uploader TEXT,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE watch_history (
                url TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                thumbnail_url TEXT,
                uploader TEXT,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                position_ms INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS favorites")
        db.execSQL("DROP TABLE IF EXISTS watch_history")
        onCreate(db)
    }
}

data class WatchHistoryEntry(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val uploader: String?,
    val durationSec: Long,
    val positionMs: Long,
    val updatedAt: Long
)

data class FavoriteEntry(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val uploader: String?,
    val durationSec: Long,
    val addedAt: Long
)

class PlayerRepository(context: Context) {
    private val dbHelper = PlayerDatabase(context.applicationContext)

    fun getWatchProgress(url: String): WatchHistoryEntry? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT url, title, thumbnail_url, uploader, duration_sec, position_ms, updated_at " +
                "FROM watch_history WHERE url = ?",
            arrayOf(url)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return WatchHistoryEntry(
                url = cursor.getString(0),
                title = cursor.getString(1),
                thumbnailUrl = cursor.getString(2),
                uploader = cursor.getString(3),
                durationSec = cursor.getLong(4),
                positionMs = cursor.getLong(5),
                updatedAt = cursor.getLong(6)
            )
        }
    }

    fun saveWatchProgress(
        url: String,
        title: String,
        thumbnailUrl: String?,
        uploader: String?,
        durationSec: Long,
        positionMs: Long
    ) {
        val values = ContentValues().apply {
            put("url", url)
            put("title", title)
            put("thumbnail_url", thumbnailUrl)
            put("uploader", uploader)
            put("duration_sec", durationSec)
            put("position_ms", positionMs)
            put("updated_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "watch_history", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteWatchHistory(url: String) {
        dbHelper.writableDatabase.delete("watch_history", "url = ?", arrayOf(url))
    }

    fun listWatchHistory(limit: Int = 100): List<WatchHistoryEntry> {
        val result = mutableListOf<WatchHistoryEntry>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT url, title, thumbnail_url, uploader, duration_sec, position_ms, updated_at " +
                "FROM watch_history ORDER BY updated_at DESC LIMIT $limit",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    WatchHistoryEntry(
                        url = cursor.getString(0),
                        title = cursor.getString(1),
                        thumbnailUrl = cursor.getString(2),
                        uploader = cursor.getString(3),
                        durationSec = cursor.getLong(4),
                        positionMs = cursor.getLong(5),
                        updatedAt = cursor.getLong(6)
                    )
                )
            }
        }
        return result
    }

    fun isFavorite(url: String): Boolean {
        dbHelper.readableDatabase.query(
            "favorites", arrayOf("url"), "url = ?", arrayOf(url), null, null, null
        ).use { return it.moveToFirst() }
    }

    fun addFavorite(url: String, title: String, thumbnailUrl: String?, uploader: String?, durationSec: Long) {
        val values = ContentValues().apply {
            put("url", url)
            put("title", title)
            put("thumbnail_url", thumbnailUrl)
            put("uploader", uploader)
            put("duration_sec", durationSec)
            put("added_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeFavorite(url: String) {
        dbHelper.writableDatabase.delete("favorites", "url = ?", arrayOf(url))
    }

    fun listFavorites(): List<FavoriteEntry> {
        val result = mutableListOf<FavoriteEntry>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT url, title, thumbnail_url, uploader, duration_sec, added_at " +
                "FROM favorites ORDER BY added_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    FavoriteEntry(
                        url = cursor.getString(0),
                        title = cursor.getString(1),
                        thumbnailUrl = cursor.getString(2),
                        uploader = cursor.getString(3),
                        durationSec = cursor.getLong(4),
                        addedAt = cursor.getLong(5)
                    )
                )
            }
        }
        return result
    }
}
