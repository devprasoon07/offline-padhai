package com.devprasoon.offlinepadhai

import android.content.Context
import android.util.Log
import com.devprasoon.offlinepadhai.db.PadhAIDatabase
import com.devprasoon.offlinepadhai.db.QuizScoreEntity

/**
 * Quiz attempts ke scores — Room me persist hote hain (100% on-device).
 * Har quiz poora hone pe QuizActivity.saveScore() bulata hai.
 */
class QuizScoreManager(private val context: Context) {

    data class QuizScore(
        val id: Long,
        val topic: String,
        val score: Int,
        val total: Int,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "QuizScoreManager"
    }

    private fun dao() = PadhAIDatabase.get(context).quizScoreDao()

    /** Ek quiz attempt ka score save karo. */
    fun saveScore(topic: String, score: Int, total: Int): Long {
        return try {
            dao().insert(
                QuizScoreEntity(
                    topic = topic,
                    score = score,
                    total = total,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "saveScore failed", e)
            -1L
        }
    }

    /** Sab scores, naye pehle (100 tak). */
    fun getScores(): List<QuizScore> {
        return try {
            dao().getAll().map {
                QuizScore(
                    id = it.id,
                    topic = it.topic,
                    score = it.score,
                    total = it.total,
                    timestamp = it.timestamp
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getScores failed", e)
            emptyList()
        }
    }

    /** Is topic pe ab tak ka best score (ya null). */
    fun bestForTopic(topic: String): Int? {
        return try {
            dao().bestForTopic(topic)
        } catch (e: Exception) {
            Log.w(TAG, "bestForTopic failed", e)
            null
        }
    }
}
