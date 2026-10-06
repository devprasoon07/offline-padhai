package com.devprasoon.offlinepadhai.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONArray
import java.io.File

/**
 * App ka local database — 100% on-device, koi server nahi.
 *
 * Tables: history (Q&A + bookmarks), quiz_scores.
 *
 * allowMainThreadQueries: HistoryActivity purane JSON wale zamane se hi
 * main thread pe padhta tha; table chhoti hai (200 rows max) to query
 * sub-millisecond hai. MainActivity ke writes pehle se IO dispatcher pe hain.
 */
@Database(entities = [HistoryEntity::class, QuizScoreEntity::class], version = 1)
abstract class PadhAIDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun quizScoreDao(): QuizScoreDao

    companion object {
        private const val TAG = "PadhAIDatabase"
        private const val DB_NAME = "padhai.db"
        private const val LEGACY_JSON = "history.json"

        @Volatile
        private var instance: PadhAIDatabase? = null

        fun get(context: Context): PadhAIDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PadhAIDatabase::class.java,
                    DB_NAME
                )
                    .allowMainThreadQueries()
                    .build()
                    .also {
                        instance = it
                        migrateLegacyJson(context.applicationContext, it)
                    }
            }
        }

        /**
         * One-time migration: purana history.json -> Room. Kaamyab hone pe
         * (ya khaali/corrupt hone pe) json file hata do taaki dobara na ho.
         */
        private fun migrateLegacyJson(appContext: Context, db: PadhAIDatabase) {
            try {
                val file = File(appContext.filesDir, LEGACY_JSON)
                if (!file.exists()) return
                val text = file.readText()
                if (text.isBlank()) {
                    file.delete()
                    return
                }
                val arr = JSONArray(text)
                var moved = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    db.historyDao().insert(
                        HistoryEntity(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            question = o.optString("question", ""),
                            answer = o.optString("answer", ""),
                            timestamp = o.optLong("timestamp", 0L),
                            bookmarked = o.optBoolean("bookmarked", false)
                        )
                    )
                    moved++
                }
                db.historyDao().trimToMax()
                file.delete()
                Log.i(TAG, "Migrated $moved history items from JSON to Room")
            } catch (e: Exception) {
                Log.w(TAG, "Legacy JSON migration failed", e)
            }
        }
    }
}
