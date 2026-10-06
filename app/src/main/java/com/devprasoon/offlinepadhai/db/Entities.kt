package com.devprasoon.offlinepadhai.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ek saved Q&A — sawal, jawab, kab, bookmarked ya nahi.
 * Purana history.json isi shape me tha; migration wahi fields uthata hai.
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val question: String,
    val answer: String,
    val timestamp: Long,
    val bookmarked: Boolean
)

/**
 * Ek quiz attempt ka score.
 */
@Entity(tableName = "quiz_scores")
data class QuizScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val score: Int,
    val total: Int,
    val timestamp: Long
)
