package com.devprasoon.offlinepadhai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.launch
import java.io.File

/**
 * Offline PadhAI — on-device AI tutor.
 *
 * Flow: CameraX photo -> ML Kit OCR -> MediaPipe Gemma -> Hinglish explanation.
 * Sab kuch phone pe, internet ki zaroorat nahi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var tutor: TutorEngine
    private lateinit var sheetBehavior: BottomSheetBehavior<View>

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var bottomSheet: NestedScrollView
    private lateinit var tvSheetHint: TextView
    private lateinit var progressOcr: ProgressBar
    private lateinit var resultContent: LinearLayout
    private lateinit var etQuestion: EditText
    private lateinit var btnExplain: Button
    private lateinit var rowThinking: LinearLayout
    private lateinit var tvAnswer: TextView
    private lateinit var dividerFollow: View
    private lateinit var tvFollowLabel: TextView
    private lateinit var followRow: LinearLayout
    private lateinit var etFollowUp: EditText
    private lateinit var btnSend: Button
    private lateinit var cardSetup: LinearLayout
    private lateinit var btnRecheck: Button

    /** Follow-up context ke liye baatcheet yaad rakho. */
    private val conversation = mutableListOf<TutorEngine.ChatTurn>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showStatus(getString(R.string.err_permission))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        sheetBehavior = BottomSheetBehavior.from(bottomSheet)
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                // Sheet khulne pe capture button halke se gayab, bandh hone pe wapas.
                val show = newState != BottomSheetBehavior.STATE_EXPANDED
                btnCapture.animate().alpha(if (show) 1f else 0f).setDuration(180)
                    .withEndAction {
                        btnCapture.visibility = if (show) View.VISIBLE else View.INVISIBLE
                    }.start()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        cameraManager = CameraManager(this, this, previewView)
        tutor = TutorEngine(this)

        btnCapture.setOnClickListener { capturePhoto() }
        btnExplain.setOnClickListener { explainQuestion() }
        btnSend.setOnClickListener { sendFollowUp() }
        btnRecheck.setOnClickListener { checkModelAndInit() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        checkModelAndInit()
    }

    // ---------- Camera ----------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        cameraManager.startCamera { message -> showStatus(message) }
    }

    private fun capturePhoto() {
        btnCapture.isEnabled = false
        cameraManager.takePhoto { file ->
            // Callback background thread pe aata hai.
            runOnUiThread {
                btnCapture.isEnabled = true
                if (file == null) {
                    showStatus(getString(R.string.err_photo))
                } else {
                    runOcr(file)
                }
            }
        }
    }

    // ---------- OCR ----------

    private fun runOcr(photo: File) {
        progressOcr.visibility = View.VISIBLE
        showStatus(getString(R.string.status_ocr_reading))
        lifecycleScope.launch {
            when (val result = OcrProcessor.recognizeImage(photo)) {
                is OcrProcessor.OcrResult.Success -> {
                    progressOcr.visibility = View.GONE
                    etQuestion.setText(result.text)
                    revealResultContent()
                    showStatus(getString(R.string.status_ocr_done))
                }
                is OcrProcessor.OcrResult.Empty -> {
                    progressOcr.visibility = View.GONE
                    showStatus(result.hint)
                }
                is OcrProcessor.OcrResult.Error -> {
                    progressOcr.visibility = View.GONE
                    showStatus(result.message)
                }
            }
        }
    }

    private fun revealResultContent() {
        if (resultContent.visibility != View.VISIBLE) {
            resultContent.visibility = View.VISIBLE
            resultContent.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.slide_up)
            )
        }
        cardSetup.visibility = View.GONE
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ---------- Tutor ----------

    private fun checkModelAndInit() {
        if (!tutor.isModelPresent()) {
            cardSetup.visibility = View.VISIBLE
            resultContent.visibility = View.GONE
            sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            return
        }
        cardSetup.visibility = View.GONE
        showStatus(getString(R.string.status_model_loading))
        lifecycleScope.launch {
            val result = tutor.init()
            if (result.isSuccess) {
                showStatus(getString(R.string.status_model_ready))
            } else {
                showStatus(getString(R.string.err_model_load))
            }
        }
    }

    private fun explainQuestion() {
        if (!tutor.isReady()) {
            if (!tutor.isModelPresent()) {
                cardSetup.visibility = View.VISIBLE
                resultContent.visibility = View.GONE
                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                showStatus(getString(R.string.err_not_ready))
            }
            return
        }
        val question = etQuestion.text.toString().trim()
        if (question.isEmpty()) {
            showStatus(getString(R.string.err_empty_question))
            return
        }
        hideKeyboard()
        conversation.clear()
        resetAnswerUi()

        tutor.explain(question, emptyList(), object : TutorEngine.StreamListener {
            override fun onPartial(fullText: String) {
                tvAnswer.text = fullText
                if (rowThinking.visibility == View.VISIBLE) {
                    rowThinking.visibility = View.GONE
                    tvAnswer.startAnimation(
                        AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in)
                    )
                }
            }

            override fun onDone() {
                btnExplain.isEnabled = true
                dividerFollow.visibility = View.VISIBLE
                tvFollowLabel.visibility = View.VISIBLE
                followRow.visibility = View.VISIBLE
                conversation.add(TutorEngine.ChatTurn(question, tvAnswer.text.toString()))
            }

            override fun onError(message: String) {
                rowThinking.visibility = View.GONE
                btnExplain.isEnabled = true
                showStatus(message)
            }
        })
    }

    private fun resetAnswerUi() {
        tvAnswer.text = ""
        tvAnswer.visibility = View.VISIBLE
        rowThinking.visibility = View.VISIBLE
        dividerFollow.visibility = View.GONE
        tvFollowLabel.visibility = View.GONE
        followRow.visibility = View.GONE
        btnExplain.isEnabled = false
    }

    private fun sendFollowUp() {
        val question = etFollowUp.text.toString().trim()
        if (question.isEmpty() || !tutor.isReady()) return
        hideKeyboard()
        etFollowUp.text?.clear()

        val baseText = tvAnswer.text.toString() + "\n\nTum: " + question + "\n"
        tvAnswer.text = baseText
        rowThinking.visibility = View.VISIBLE
        btnSend.isEnabled = false

        tutor.askFollowUp(question, conversation.toList(), object : TutorEngine.StreamListener {
            override fun onPartial(fullText: String) {
                tvAnswer.text = baseText + fullText
                if (rowThinking.visibility == View.VISIBLE) {
                    rowThinking.visibility = View.GONE
                }
            }

            override fun onDone() {
                btnSend.isEnabled = true
                conversation.add(
                    TutorEngine.ChatTurn(question, tvAnswer.text.toString().removePrefix(baseText))
                )
            }

            override fun onError(message: String) {
                rowThinking.visibility = View.GONE
                btnSend.isEnabled = true
                showStatus(message)
            }
        })
    }

    // ---------- Helpers ----------

    private fun showStatus(message: String) {
        tvSheetHint.text = message
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        bottomSheet = findViewById(R.id.bottomSheet)
        tvSheetHint = findViewById(R.id.tvSheetHint)
        progressOcr = findViewById(R.id.progressOcr)
        resultContent = findViewById(R.id.resultContent)
        etQuestion = findViewById(R.id.etQuestion)
        btnExplain = findViewById(R.id.btnExplain)
        rowThinking = findViewById(R.id.rowThinking)
        tvAnswer = findViewById(R.id.tvAnswer)
        dividerFollow = findViewById(R.id.dividerFollow)
        tvFollowLabel = findViewById(R.id.tvFollowLabel)
        followRow = findViewById(R.id.followRow)
        etFollowUp = findViewById(R.id.etFollowUp)
        btnSend = findViewById(R.id.btnSend)
        cardSetup = findViewById(R.id.cardSetup)
        btnRecheck = findViewById(R.id.btnRecheck)
    }

    override fun onDestroy() {
        cameraManager.shutdown()
        tutor.close()
        super.onDestroy()
    }
}
