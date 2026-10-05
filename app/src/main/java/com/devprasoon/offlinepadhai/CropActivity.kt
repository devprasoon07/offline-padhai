package com.devprasoon.offlinepadhai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Apna khud ka crop screen — koi bahari library nahi.
 * Photo dikhti hai, ungli se sawal wala hissa select karo,
 * "Crop karo" dabao — sirf wahi hissa OCR ke liye jayega.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_PATH = "photo_path"
        const val EXTRA_CROP_PATH = "crop_path"
    }

    private lateinit var imageView: ImageView
    private lateinit var overlay: CropOverlayView
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        imageView = findViewById(R.id.cropImage)
        overlay = findViewById(R.id.cropOverlay)

        val photoFile = intent.getStringExtra(EXTRA_PHOTO_PATH)?.let { File(it) }
        if (photoFile == null || !photoFile.exists()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        bitmap = decodeBounded(photoFile, 2000)
        if (bitmap == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        imageView.setImageBitmap(bitmap)

        findViewById<Button>(R.id.btnCropDone).setOnClickListener { doCrop() }
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun doCrop() {
        val bmp = bitmap
        if (bmp == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val viewRect = overlay.selection
        if (viewRect == null || viewRect.width() < 40f || viewRect.height() < 40f) {
            Toast.makeText(this, "Pehle ungli se hissa select karo", Toast.LENGTH_SHORT).show()
            return
        }

        // View coordinates -> bitmap coordinates (ImageView ka matrix ulta karo).
        val inverse = Matrix()
        imageView.imageMatrix.invert(inverse)
        val pts = floatArrayOf(viewRect.left, viewRect.top, viewRect.right, viewRect.bottom)
        inverse.mapPoints(pts)
        val left = pts[0].toInt().coerceIn(0, bmp.width - 1)
        val top = pts[1].toInt().coerceIn(0, bmp.height - 1)
        val right = pts[2].toInt().coerceIn(0, bmp.width)
        val bottom = pts[3].toInt().coerceIn(0, bmp.height)
        if (right - left < 20 || bottom - top < 20) {
            Toast.makeText(this, "Selection bahut chhota hai", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        val outFile = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (cropped != bmp) cropped.recycle()

        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CROP_PATH, outFile.absolutePath))
        finish()
    }

    private fun decodeBounded(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    override fun onDestroy() {
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }
}
