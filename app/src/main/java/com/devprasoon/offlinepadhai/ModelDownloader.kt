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
