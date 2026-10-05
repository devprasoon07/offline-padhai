package com.devprasoon.offlinepadhai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Q&A history: har successful explanation auto-save hoti hai.
 *
 * Storage: filesDir/history.json me JSONArray (org.json — Android me built-in,
 * koi nayi dependency nahi). Sab file IO try/catch me hai — kabhi crash nahi.
 * Thread-safe: sab public methods @Synchronized.
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
        private const val FILE_NAME = "history.json"
        private const val MAX_ITEMS = 200
    }

    private fun historyFile(): File = File(context.filesDir, FILE_NAME)

    /** Naya Q&A save karo. List me sabse upar aayega. */
    @Synchronized
    fun save(question: String, answer: String): HistoryItem {
        val item = HistoryItem(
            id = UUID.randomUUID().toString(),
            question = question,
            answer = answer,
            timestamp = System.currentTimeMillis(),
            bookmarked = false
        )
        try {
            val items = readAll().toMutableList()
            items.add(0, item)
            while (items.size > MAX_ITEMS) {
                items.removeAt(items.size - 1)
            }
            writeAll(items)
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
        return item
    }

    /** Sab items, naye pehle. */
    @Synchronized
    fun getAll(): List<HistoryItem> {
        return try {
            readAll()
        } catch (e: Exception) {
            Log.w(TAG, "getAll failed", e)
            emptyList()
        }
    }

    /** Bookmark toggle — nayi state return karta hai. */
    @Synchronized
    fun toggleBookmark(id: String): Boolean {
        var newState = false
        try {
            val items = readAll().toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val old = items[idx]
                newState = !old.bookmarked
                items[idx] = old.copy(bookmarked = newState)
                writeAll(items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "toggleBookmark failed", e)
        }
        return newState
    }

    /** Maujooda item ka jawab update karo (follow-up ke baad). */
    @Synchronized
    fun updateAnswer(id: String, answer: String) {
        try {
            val items = readAll().toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items[idx] = items[idx].copy(answer = answer)
                writeAll(items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateAnswer failed", e)
        }
    }

    @Synchronized
    fun delete(id: String) {
        try {
            writeAll(readAll().filter { it.id != id })
        } catch (e: Exception) {
            Log.w(TAG, "delete failed", e)
        }
    }

    @Synchronized
    fun clear() {
        try {
            writeAll(emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "clear failed", e)
        }
    }

    private fun readAll(): List<HistoryItem> {
        val file = historyFile()
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<HistoryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                HistoryItem(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    question = o.optString("question", ""),
                    answer = o.optString("answer", ""),
                    timestamp = o.optLong("timestamp", 0L),
                    bookmarked = o.optBoolean("bookmarked", false)
                )
            )
        }
        return out
    }

    private fun writeAll(items: List<HistoryItem>) {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("question", it.question)
            o.put("answer", it.answer)
            o.put("timestamp", it.timestamp)
            o.put("bookmarked", it.bookmarked)
            arr.put(o)
        }
        historyFile().writeText(arr.toString())
    }
}
