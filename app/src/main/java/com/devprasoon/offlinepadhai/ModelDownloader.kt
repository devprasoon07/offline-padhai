package com.devprasoon.offlinepadhai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest

/**
 * Model file downloader — Android DownloadManager pe based.
 *
 * - System DownloadManager: app band ho ya phone restart ho, download
 *   chalta rehta hai aur resume hota hai (resumable).
 * - Shuru karne se pehle free-space check (model size + 20% buffer).
 * - Download poora hone pe SHA-256 verify (catalog me ho to).
 * - Tampered/incomplete file kabhi load nahi hoti — TutorEngine
 *   dobara verify karta hai.
 */
class ModelDownloader(private val context: Context) {

    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val PREFS = "padhai_prefs"
        private const val KEY_DL_ID = "model_download_id"
        private const val KEY_DL_MODEL = "model_download_model"
        private const val SPACE_BUFFER_MULT = 1.2
        private const val POLL_MS = 500L
    }

    private val dm =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var activeModel: AIModel? = null
    private var progressRunnable: Runnable? = null
    private var receiver: BroadcastReceiver? = null

    /** Naya download shuru karo. Pehle se chal raha ho to cancel karke naya. */
    fun start(model: AIModel, listener: Listener) {
        cancelQuietly()
        if (model.downloadUrl.isBlank()) {
            listener.onError("Is model ka download link abhi set nahi hai.")
            return
        }
        val destDir = context.getExternalFilesDir(null) ?: run {
            listener.onError("Storage nahi mil rahi.")
            return
        }
        val destFile = File(destDir, model.fileName)
        // Pehle se sahi file hai to dobara download mat karo.
        if (destFile.exists() && destFile.length() > model.sizeBytes * 9 / 10 &&
            verifySha256(destFile, model.sha256)
        ) {
            listener.onComplete(destFile)
            return
        }
        if (destFile.exists()) destFile.delete()
        // Free-space check: model size + 20% buffer.
        val need = (model.sizeBytes * SPACE_BUFFER_MULT).toLong()
        if (destDir.usableSpace < need) {
            val needGb = need / 1_000_000_000.0
            listener.onError("Jagah kam hai — %.1f GB khali karo.".format(needGb))
            return
        }
        val req = DownloadManager.Request(Uri.parse(model.downloadUrl)).apply {
            setTitle("PadhAI model download")
            setDescription("${model.modelName} (${model.sizeLabel})")
            setDestinationUri(Uri.fromFile(destFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
        }
        val id = dm.enqueue(req)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DL_ID, id)
            .putString(KEY_DL_MODEL, model.id)
            .apply()
        this.listener = listener
        this.activeModel = model
        registerReceiver(id)
        startProgressPolling(id)
        Log.i(TAG, "Download enqueued: ${model.id} id=$id")
    }

    /**
     * App restart ke baad adhure download se dobara judo (progress dikhane ke liye).
     * Koi active download na ho to false.
     */
    fun attachIfActive(listener: Listener): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_DL_ID, -1L)
        if (id == -1L) return false
        val model = ModelCatalog.byId(prefs.getString(KEY_DL_MODEL, null))
        val status = queryStatus(id)
        if (status == null || status == DownloadManager.STATUS_SUCCESSFUL ||
            status == DownloadManager.STATUS_FAILED
        ) {
            clearPrefs()
            return false
        }
        this.listener = listener
        this.activeModel = model
        registerReceiver(id)
        startProgressPolling(id)
        return true
    }

    fun cancel() {
        cancelQuietly()
        listener?.onError("Download cancel kiya gaya.")
    }

    /**
     * Activity destroy pe receiver/polling hatao — DownloadManager ka
     * background download chalta rehta hai, agli onCreate me attachIfActive
     * se dobara jud jayenge.
     */
    fun detach() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        listener = null
    }

    private fun cancelQuietly() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_DL_ID, -1L)
        if (id != -1L) {
            try { dm.remove(id) } catch (_: Exception) {}
        }
        clearPrefs()
        listener = null
        activeModel = null
    }

    private fun clearPrefs() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DL_ID).remove(KEY_DL_MODEL).apply()
    }

    private fun registerReceiver(expectId: Long) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != expectId) return
                handleCompletion(id)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun startProgressPolling(id: Long) {
        val r = object : Runnable {
            override fun run() {
                val (done, total) = queryProgress(id)
                if (done >= 0) {
                    listener?.onProgress(done, total)
                    progressRunnable = this
                    mainHandler.postDelayed(this, POLL_MS)
                }
            }
        }
        progressRunnable = r
        mainHandler.post(r)
    }

    private fun queryProgress(id: Long): Pair<Long, Long> {
        val q = DownloadManager.Query().setFilterById(id)
        return try {
            dm.query(q).use { c ->
                if (c.moveToFirst()) {
                    val done = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    var total = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (total <= 0) total = activeModel?.sizeBytes ?: -1L
                    done to total
                } else -1L to -1L
            }
        } catch (_: Exception) { -1L to -1L }
    }

    private fun queryStatus(id: Long): Int? {
        val q = DownloadManager.Query().setFilterById(id)
        return try {
            dm.query(q).use { c ->
                if (c.moveToFirst()) c.getInt(
                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                ) else null
            }
        } catch (_: Exception) { null }
    }

    private fun handleCompletion(id: Long) {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
        val model = activeModel
        val l = listener
        cancelQuietly()
        if (model == null || l == null) return
        val destFile = File(context.getExternalFilesDir(null), model.fileName)
        val status = queryStatus(id)
        if (status != DownloadManager.STATUS_SUCCESSFUL || !destFile.exists()) {
            l.onError("Download poora nahi hua. Dobara try karo.")
            return
        }
        if (!verifySha256(destFile, model.sha256)) {
            destFile.delete()
            l.onError("File corrupt lagi (SHA mismatch) — dobara download karo.")
            return
        }
        Log.i(TAG, "Download complete + verified: ${model.id}")
        l.onComplete(destFile)
    }

    companion object {
        /** SHA-256 verify — expected khali ho to check skip (dev builds). */
        fun verifySha256(file: File, expectedHex: String): Boolean {
            if (expectedHex.isBlank()) return true
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { ins ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) {
                        digest.update(buf, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                actual.equals(expectedHex.trim(), ignoreCase = true)
            } catch (e: Exception) {
                Log.w("ModelDownloader", "SHA check failed", e)
                false
            }
        }
    }
}
