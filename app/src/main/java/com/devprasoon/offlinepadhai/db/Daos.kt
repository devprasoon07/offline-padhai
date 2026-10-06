package com.devprasoon.offlinepadhai.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 200")
    fun getAll(): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE bookmarked = 1 ORDER BY timestamp DESC")
    fun getBookmarked(): List<HistoryEntity>

    @Query("UPDATE history SET bookmarked = :bookmarked WHERE id = :id")
    fun setBookmarked(id: String, bookmarked: Boolean)

    @Query("SELECT bookmarked FROM history WHERE id = :id")
    fun isBookmarked(id: String): Boolean?

    @Query("UPDATE history SET answer = :answer WHERE id = :id")
    fun updateAnswer(id: String, answer: String)

    @Query("DELETE FROM history WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM history")
    fun clear()

    @Query("SELECT COUNT(*) FROM history")
    fun count(): Int

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC LIMIT 200)")
    fun trimToMax()
}

@Dao
interface QuizScoreDao {
    @Insert
    fun insert(score: QuizScoreEntity): Long

    @Query("SELECT * FROM quiz_scores ORDER BY timestamp DESC LIMIT 100")
    fun getAll(): List<QuizScoreEntity>

    @Query("SELECT MAX(score) FROM quiz_scores WHERE topic = :topic")
    fun bestForTopic(topic: String): Int?
}
