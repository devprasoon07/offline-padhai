package com.devprasoon.offlinepadhai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History screen: purane Q&A ki list.
 *
 * - Tap item → MainActivity me wapas, sawal+jawab load ho jata hai.
 * - Star tap → bookmark toggle. Switch → sirf bookmarks dikhao.
 * - Delete icon ya long-press → confirmation ke baad delete.
 *
 * Note: ListView use kiya hai (framework, koi nayi dependency nahi).
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_BOOKMARKED = "bookmarked"
    }

    private lateinit var historyManager: HistoryManager
    private lateinit var adapter: HistoryAdapter
    private var showBookmarksOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyManager = HistoryManager(this)

        val listView: ListView = findViewById(R.id.historyList)
        val emptyView: TextView = findViewById(R.id.tvHistoryEmpty)
        val filterSwitch: Switch = findViewById(R.id.switchBookmarks)

        listView.emptyView = emptyView
        adapter = HistoryAdapter()
        listView.adapter = adapter

        filterSwitch.setOnCheckedChangeListener { _, checked ->
            showBookmarksOnly = checked
            refresh()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            val intent = Intent().apply {
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_QUESTION, item.question)
                putExtra(EXTRA_ANSWER, item.answer)
                putExtra(EXTRA_BOOKMARKED, item.bookmarked)
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
        }

        refresh()
    }

    private fun refresh() {
        val all = historyManager.getAll()
        val shown = if (showBookmarksOnly) all.filter { it.bookmarked } else all
        adapter.setItems(shown)
    }

    private fun onStarTap(item: HistoryManager.HistoryItem) {
        historyManager.toggleBookmark(item.id)
        refresh()
    }

    private fun confirmDelete(item: HistoryManager.HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_msg))
            .setPositiveButton(getString(R.string.delete_yes)) { _, _ ->
                historyManager.delete(item.id)
                refresh()
            }
            .setNegativeButton(getString(R.string.delete_no), null)
            .show()
    }

    private inner class HistoryAdapter : BaseAdapter() {

        private var items: List<HistoryManager.HistoryItem> = emptyList()
        private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

        fun setItems(newItems: List<HistoryManager.HistoryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): HistoryManager.HistoryItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            val item = getItem(position)

            val tvQuestion: TextView = view.findViewById(R.id.tvHistQuestion)
            val tvDate: TextView = view.findViewById(R.id.tvHistDate)
            val btnStar: ImageButton = view.findViewById(R.id.btnHistStar)
            val btnDelete: ImageButton = view.findViewById(R.id.btnHistDelete)

            tvQuestion.text = item.question
            tvDate.text = dateFormat.format(Date(item.timestamp))
            btnStar.setImageResource(
                if (item.bookmarked) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            btnStar.contentDescription = getString(R.string.bookmark_desc)
            btnStar.setOnClickListener { onStarTap(item) }
            btnDelete.setOnClickListener { confirmDelete(item) }

            return view
        }
    }
}
