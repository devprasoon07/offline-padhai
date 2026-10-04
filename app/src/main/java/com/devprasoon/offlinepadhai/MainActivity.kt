package com.devprasoon.offlinepadhai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Offline PadhAI — on-device AI tutor.
 * Flow: CameraX photo -> ML Kit OCR -> MediaPipe Gemma -> Hinglish explanation.
 * TODO (48-hr build): wire CameraManager -> OcrProcessor -> TutorEngine below.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: setContentView with camera preview + result UI
    }
}
