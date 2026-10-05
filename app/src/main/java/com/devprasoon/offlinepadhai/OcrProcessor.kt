package com.devprasoon.offlinepadhai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device OCR: photo se sawal ka text nikalta hai.
 *
 * Pehle Devanagari recognizer (Hindi textbooks), text na mile to
 * Latin fallback. Dono recognizer fully on-device hain — internet
 * ki zaroorat nahi. Badi photos ko pehle downscale kiya jata hai
 * taaki mid-range phones pe bhi tez chale.
 */
object OcrProcessor {

    sealed class OcrResult {
        data class Success(val text: String) : OcrResult()
        data class Empty(val hint: String) : OcrResult()
        data class Error(val message: String) : OcrResult()
    }

    /** OCR se pehle lambi side itne px se zyada nahi rakhenge. */
    private const val MAX_SIDE_PX = 1600

    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    /** Photo file se text nikalo. */
    suspend fun recognizeImage(photoFile: File): OcrResult = withContext(Dispatchers.IO) {
        val bitmap = decodeBounded(photoFile)
            ?: return@withContext OcrResult.Error("Photo khul nahi payi.")
        recognize(bitmap)
    }

    private suspend fun recognize(bitmap: Bitmap): OcrResult {
        return try {
            val devanagariText = recognizeWith(bitmap, devanagariRecognizer).trim()
            val text = if (devanagariText.isNotEmpty()) {
                devanagariText
            } else {
                recognizeWith(bitmap, latinRecognizer).trim()
            }
            val cleaned = cleanText(text)
            if (cleaned.isEmpty()) {
                OcrResult.Empty("Koi saaf text nahi mila — roshni thik karke phir se photo lo.")
            } else {
                OcrResult.Success(cleaned)
            }
        } catch (e: Exception) {
            OcrResult.Error("Text padhne me dikkat aayi.")
        }
    }

    /**
     * OCR ki kachra lines hatao — single characters ya be-matlab tukde
     * (jaise "I", "क", "o") model ko confuse karte hain aur jawab kharab hota hai.
     * Aisi line rakho jisme kam se kam 3 letters/digits hon.
     */
    private fun cleanText(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { line -> line.count { it.isLetterOrDigit() } >= 3 }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private suspend fun recognizeWith(bitmap: Bitmap, recognizer: TextRecognizer): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text.orEmpty()
    }

    /** Memory bachane ke liye badi photo ko sample karke decode karo. */
    private fun decodeBounded(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val (width, height) = bounds.outWidth to bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        while (width / sampleSize > MAX_SIDE_PX || height / sampleSize > MAX_SIDE_PX) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }
}
