package com.devprasoon.offlinepadhai

import android.content.Context
import android.util.Log
import com.devprasoon.offlinepadhai.db.HistoryEntity
import com.devprasoon.offlinepadhai.db.PadhAIDatabase
import java.util.UUID

/**
 * Q&A history: har successful explanation auto-save hoti hai.
 *
 * Storage: Room database (padhai.db) — 100% on-device.
 * Purana history.json pehli Room access pe auto-migrate ho jata hai.
 * Public API JSON wale zamane jaisi hi hai — callers me koi change nahi.
 * Sab DB kaam try/catch me hai — kabhi crash nahi.
 */
class HistoryManager(private val context: Context) {

    data class HistoryItem(
        val id: String,
        val question: String,
        val answer: String,
        val timestamp: Long,
        val bookmarked: Boolean
    )

    companion object {
        private const val TAG = "HistoryManager"
    }

    private fun dao() = PadhAIDatabase.get(context).historyDao()

    private fun HistoryEntity.toItem() = HistoryItem(
        id = id,
        question = question,
        answer = answer,
        timestamp = timestamp,
        bookmarked = bookmarked
    )

    /** Naya Q&A save karo. List me sabse upar aayega. */
    fun save(question: String, answer: String): HistoryItem {
        val item = HistoryItem(
            id = UUID.randomUUID().toString(),
            question = question,
            answer = answer,
            timestamp = System.currentTimeMillis(),
            bookmarked = false
        )
        try {
            dao().insert(
                HistoryEntity(
                    id = item.id,
                    question = item.question,
                    answer = item.answer,
                    timestamp = item.timestamp,
                    bookmarked = false
                )
            )
            dao().trimToMax()
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
        return item
    }

    /** Sab items, naye pehle. */
    fun getAll(): List<HistoryItem> {
        return try {
            dao().getAll().map { it.toItem() }
        } catch (e: Exception) {
            Log.w(TAG, "getAll failed", e)
            emptyList()
        }
    }

    /** Sirf bookmarked items, naye pehle. */
    fun getBookmarked(): List<HistoryItem> {
        return try {
            dao().getBookmarked().map { it.toItem() }
        } catch (e: Exception) {
            Log.w(TAG, "getBookmarked failed", e)
            emptyList()
        }
    }

    /** Bookmark toggle — nayi state return karta hai. */
    fun toggleBookmark(id: String): Boolean {
        var newState = false
        try {
            val current = dao().isBookmarked(id) ?: return false
            newState = !current
            dao().setBookmarked(id, newState)
        } catch (e: Exception) {
            Log.w(TAG, "toggleBookmark failed", e)
        }
        return newState
    }

    /** Maujooda item ka jawab update karo (follow-up ke baad). */
    fun updateAnswer(id: String, answer: String) {
        try {
            dao().updateAnswer(id, answer)
        } catch (e: Exception) {
            Log.w(TAG, "updateAnswer failed", e)
        }
    }

    fun delete(id: String) {
        try {
            dao().delete(id)
        } catch (e: Exception) {
            Log.w(TAG, "delete failed", e)
        }
    }

    fun clear() {
        try {
            dao().clear()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed", e)
        }
    }
}
