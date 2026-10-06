package com.devprasoon.offlinepadhai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Offline PadhAI — on-device AI tutor.
 *
 * Flow: CameraX photo -> ML Kit OCR -> MediaPipe Gemma -> Hinglish explanation.
 * Sab kuch phone pe, internet ki zaroorat nahi.
 *
 * Features: History (auto-save), Bookmarks, Quiz mode, Voice input + TTS, Share.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var tutor: TutorEngine
    private lateinit var sheetBehavior: BottomSheetBehavior<View>
    private lateinit var historyManager: HistoryManager

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnHistory: MaterialButton
    private lateinit var btnQuiz: MaterialButton
    private lateinit var bottomSheet: NestedScrollView
    private lateinit var tvSheetHint: TextView
    private lateinit var progressOcr: ProgressBar
    private lateinit var inputContent: LinearLayout
    private lateinit var resultContent: LinearLayout
    private lateinit var etQuestion: EditText
    private lateinit var btnExplain: Button
    private lateinit var spinnerLanguage: Spinner
    private lateinit var rowThinking: LinearLayout
    private lateinit var tvAnswer: TextView
    private lateinit var rowAnswerActions: LinearLayout
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnSpeak: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var dividerFollow: View
    private lateinit var tvFollowLabel: TextView
    private lateinit var followRow: LinearLayout
    private lateinit var etFollowUp: EditText
    private lateinit var btnSend: Button
    private lateinit var cardSetup: LinearLayout
    private lateinit var btnRecheck: Button
    private lateinit var tvSetupBody: TextView
    private lateinit var rgModels: RadioGroup
    private lateinit var rbModelLite: RadioButton
    private lateinit var rbModelStandard: RadioButton
    private lateinit var rbModelPro: RadioButton
    private lateinit var btnDownloadModel: Button
    private lateinit var pbDownload: ProgressBar
    private lateinit var tvDownloadStatus: TextView
    private var modelDownloader: ModelDownloader? = null
    private var pickerInitDone = false

    /** Follow-up context ke liye baatcheet yaad rakho. */
    private val conversation = mutableListOf<TutorEngine.ChatTurn>()

    /** Abhi screen pe jo Q&A hai, uska history id (bookmark ke liye). */
    private var currentHistoryId: String? = null

    /** TTS */
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Crop cancel/error pe wapas OCR ke liye original photo yaad rakho. */
    private var pendingCropSource: File? = null

    /** Streaming smoothness: har token pe TextView update karne se UI atakti hai,
        isliye ~120ms me ek baar update karo. latestFullAnswer me hamesha poora text. */
    private var latestFullAnswer = ""
    private var lastAnswerUiUpdate = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showStatus(getString(R.string.err_permission))
            }
        }

    /** History se wapas — sawal+jawab load karo. */
    private val historyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val id = data.getStringExtra(HistoryActivity.EXTRA_ID).orEmpty()
                val q = data.getStringExtra(HistoryActivity.EXTRA_QUESTION).orEmpty()
                val a = data.getStringExtra(HistoryActivity.EXTRA_ANSWER).orEmpty()
                val bookmarked = data.getBooleanExtra(HistoryActivity.EXTRA_BOOKMARKED, false)
                if (q.isNotEmpty() || a.isNotEmpty()) {
                    loadHistoryItem(id, q, a, bookmarked)
                }
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
        historyManager = HistoryManager(this)
        initTts()

        btnCapture.setOnClickListener { capturePhoto() }
        btnHistory.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }
        btnQuiz.setOnClickListener {
            // Model abhi load ho raha ho to Quiz mat kholo — warna 1.5 GB model
            // do baar RAM me load hokar OOM crash ho sakta hai (sharedLlm abhi null hai).
            if (!tutor.isReady()) {
                if (!tutor.isModelPresent()) {
                    cardSetup.visibility = View.VISIBLE
                    inputContent.visibility = View.GONE
                    resultContent.visibility = View.GONE
                    sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                } else {
                    showStatus(getString(R.string.err_not_ready))
                }
                return@setOnClickListener
            }
            startActivity(Intent(this, QuizActivity::class.java))
        }
        btnExplain.setOnClickListener { explainQuestion() }
        btnSend.setOnClickListener { sendFollowUp() }
        btnRecheck.setOnClickListener { checkModelAndInit() }
        setupModelPicker()
        // App restart ke baad adhura download ho to usse jud jao.
        modelDownloader = ModelDownloader(this)
        modelDownloader?.attachIfActive(downloadListener())
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnSpeak.setOnClickListener { toggleSpeak() }
        btnShare.setOnClickListener { shareAnswer() }

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
                    openCrop(file)
                }
            }
        }
    }

    /**
     * Photo lene ke baad apna crop screen kholo — ungli se sirf sawal wala
     * hissa chuno taaki OCR saaf text pakde. Cancel pe poori photo pe OCR.
     */
    private fun openCrop(photo: File) {
        pendingCropSource = photo
        val intent = Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_PHOTO_PATH, photo.absolutePath)
        cropLauncher.launch(intent)
    }

    /** CropActivity ka result: cropped hissa mila to uspe OCR, cancel pe original. */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = pendingCropSource
        pendingCropSource = null
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(CropActivity.EXTRA_CROP_PATH)
            val cropFile = path?.let { File(it) }
            if (cropFile != null && cropFile.exists()) {
                runOcr(cropFile)
            } else if (source != null) {
                runOcr(source)
            } else {
                showStatus(getString(R.string.err_photo))
            }
        } else {
            // Cancel: poori original photo pe OCR kar lo.
            if (source != null) runOcr(source)
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

    /** OCR/voice ke baad sheet kholo taaki sawal dikhe. Input hamesha visible hai. */
    private fun revealResultContent() {
        cardSetup.visibility = View.GONE
        if (inputContent.visibility != View.VISIBLE) {
            inputContent.visibility = View.VISIBLE
        }
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ---------- Tutor ----------

    private fun checkModelAndInit() {
        if (!tutor.isModelPresent()) {
            refreshModelPicker()
            cardSetup.visibility = View.VISIBLE
            inputContent.visibility = View.GONE
            resultContent.visibility = View.GONE
            sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            return
        }
        cardSetup.visibility = View.GONE
        inputContent.visibility = View.VISIBLE
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

    /** Model picker: 3 tiers — Lite / Standard / Pro. Choice save rehti hai. */
    private fun setupModelPicker() {
        val lite = ModelCatalog.MODELS[0]
        val std = ModelCatalog.MODELS[1]
        val pro = ModelCatalog.MODELS[2]
        rbModelLite.text = "${lite.displayName} — ${lite.modelName} (${lite.sizeLabel})\n${lite.blurb}"
        rbModelStandard.text = "${std.displayName} — ${std.modelName} (${std.sizeLabel})\n${std.blurb}"
        rbModelPro.text = "${pro.displayName} — ${pro.modelName} (${pro.sizeLabel})\n${pro.blurb}"
        refreshModelPicker()
        rgModels.setOnCheckedChangeListener { _, checkedId ->
            if (!pickerInitDone) return@setOnCheckedChangeListener
            val newId = when (checkedId) {
                R.id.rbModelLite -> "lite"
                R.id.rbModelPro -> "pro"
                else -> "standard"
            }
            if (AppPrefs.getModelId(this) != newId) {
                AppPrefs.setModelId(this, newId)
                tutor.notifyModelChanged()
                pbDownload.visibility = View.GONE
                tvDownloadStatus.visibility = View.GONE
            }
        }
        pickerInitDone = true
        btnDownloadModel.setOnClickListener { startModelDownload() }
    }

    private fun refreshModelPicker() {
        when (AppPrefs.getModelId(this)) {
            "lite" -> rgModels.check(R.id.rbModelLite)
            "pro" -> rgModels.check(R.id.rbModelPro)
            else -> rgModels.check(R.id.rbModelStandard)
        }
        tvSetupBody.text = getString(R.string.setup_body_new)
    }

    private fun downloadListener() = object : ModelDownloader.Listener {
        override fun onProgress(downloadedBytes: Long, totalBytes: Long) {
            runOnUiThread {
                pbDownload.visibility = View.VISIBLE
                tvDownloadStatus.visibility = View.VISIBLE
                if (totalBytes > 0) {
                    val pct = (downloadedBytes * 100 / totalBytes).toInt()
                    pbDownload.progress = pct
                    val mb = downloadedBytes / 1_000_000
                    val tot = totalBytes / 1_000_000
                    tvDownloadStatus.text = "Download ho raha hai… $pct% ($mb/$tot MB)"
                } else {
                    tvDownloadStatus.text = "Download ho raha hai…"
                }
            }
        }

        override fun onComplete(file: File) {
            runOnUiThread {
                tvDownloadStatus.text = getString(R.string.dl_complete)
                pbDownload.visibility = View.GONE
                checkModelAndInit()
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                pbDownload.visibility = View.GONE
                tvDownloadStatus.visibility = View.VISIBLE
                tvDownloadStatus.text = message
            }
        }
    }

    private fun startModelDownload() {
        val model = AppPrefs.getModel(this)
        if (model.downloadUrl.isBlank()) {
            tvDownloadStatus.visibility = View.VISIBLE
            tvDownloadStatus.text = getString(R.string.dl_no_link)
            return
        }
        tvDownloadStatus.visibility = View.VISIBLE
        tvDownloadStatus.text = getString(R.string.dl_starting)
        pbDownload.visibility = View.VISIBLE
        pbDownload.progress = 0
        modelDownloader = ModelDownloader(this)
        modelDownloader?.start(model, downloadListener())
    }

    private fun explainQuestion() {
        if (!tutor.isReady()) {
            if (!tutor.isModelPresent()) {
                cardSetup.visibility = View.VISIBLE
                inputContent.visibility = View.GONE
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
        // Direct query me bhi thinking/answer dikhe — result section kholo.
        if (resultContent.visibility != View.VISIBLE) {
            resultContent.visibility = View.VISIBLE
        }

        tutor.explain(question, emptyList(), AppPrefs.getLanguage(this), object : TutorEngine.StreamListener {
            override fun onPartial(fullText: String) {
                updateAnswerThrottled(fullText)
                if (rowThinking.visibility == View.VISIBLE) {
                    rowThinking.visibility = View.GONE
                    tvAnswer.startAnimation(
                        AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in)
                    )
                }
            }

            override fun onDone() {
                flushAnswer()
                btnExplain.isEnabled = true
                dividerFollow.visibility = View.VISIBLE
                tvFollowLabel.visibility = View.VISIBLE
                followRow.visibility = View.VISIBLE
                rowAnswerActions.visibility = View.VISIBLE
                val finalAnswer = tvAnswer.text.toString()
                conversation.add(TutorEngine.ChatTurn(question, finalAnswer))
                // History me auto-save (background me, UI block nahi)
                lifecycleScope.launch {
                    val item = withContext(Dispatchers.IO) {
                        historyManager.save(question, finalAnswer)
                    }
                    currentHistoryId = item.id
                    updateBookmarkIcon(item.bookmarked)
                }
            }

            override fun onError(message: String) {
                rowThinking.visibility = View.GONE
                btnExplain.isEnabled = true
                showStatus(message)
            }
        })
    }

    private fun resetAnswerUi() {
        latestFullAnswer = ""
        lastAnswerUiUpdate = 0L
        tvAnswer.text = ""
        tvAnswer.visibility = View.VISIBLE
        rowThinking.visibility = View.VISIBLE
        dividerFollow.visibility = View.GONE
        tvFollowLabel.visibility = View.GONE
        followRow.visibility = View.GONE
        rowAnswerActions.visibility = View.GONE
        btnExplain.isEnabled = false
        // Naya sawal = nayi history entry banegi
        currentHistoryId = null
        updateBookmarkIcon(false)
    }

    /**
     * Streaming ke dauraan TextView ko ~120ms me ek baar update karo.
     * Har token pe setText() karne se layout pass bar-bar chalta hai aur UI laggy lagti hai.
     */
    private fun updateAnswerThrottled(full: String) {
        latestFullAnswer = full
        val now = SystemClock.uptimeMillis()
        if (now - lastAnswerUiUpdate >= 120) {
            lastAnswerUiUpdate = now
            tvAnswer.text = full
        }
    }

    /** Throttle ki wajah se chhuta hua aakhri text onDone pe laga do. */
    private fun flushAnswer() {
        if (tvAnswer.text.toString() != latestFullAnswer) {
            tvAnswer.text = latestFullAnswer
        }
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

        tutor.askFollowUp(question, conversation.toList(), AppPrefs.getLanguage(this), object : TutorEngine.StreamListener {
            override fun onPartial(fullText: String) {
                updateAnswerThrottled(baseText + fullText)
                if (rowThinking.visibility == View.VISIBLE) {
                    rowThinking.visibility = View.GONE
                }
            }

            override fun onDone() {
                flushAnswer()
                btnSend.isEnabled = true
                val newAnswer = tvAnswer.text.toString().removePrefix(baseText)
                conversation.add(TutorEngine.ChatTurn(question, newAnswer))
                // History entry ka jawab update karo
                val id = currentHistoryId
                if (id != null) {
                    val fullText = tvAnswer.text.toString()
                    lifecycleScope.launch(Dispatchers.IO) {
                        historyManager.updateAnswer(id, fullText)
                    }
                }
            }

            override fun onError(message: String) {
                rowThinking.visibility = View.GONE
                btnSend.isEnabled = true
                showStatus(message)
            }
        })
    }

    // ---------- History se load ----------

    private fun loadHistoryItem(id: String, question: String, answer: String, bookmarked: Boolean) {
        hideKeyboard()
        currentHistoryId = id.ifEmpty { null }
        etQuestion.setText(question)
        tvAnswer.text = answer
        tvAnswer.visibility = View.VISIBLE
        rowThinking.visibility = View.GONE
        if (resultContent.visibility != View.VISIBLE) {
            resultContent.visibility = View.VISIBLE
        }
        cardSetup.visibility = View.GONE
        dividerFollow.visibility = View.VISIBLE
        tvFollowLabel.visibility = View.VISIBLE
        followRow.visibility = View.VISIBLE
        rowAnswerActions.visibility = if (answer.isNotEmpty()) View.VISIBLE else View.GONE
        btnExplain.isEnabled = true
        updateBookmarkIcon(bookmarked)
        // Follow-up isi context me chale
        conversation.clear()
        if (question.isNotEmpty() && answer.isNotEmpty()) {
            conversation.add(TutorEngine.ChatTurn(question, answer))
        }
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ---------- Bookmark ----------

    private fun toggleBookmark() {
        val q = etQuestion.text.toString().trim()
        val a = tvAnswer.text.toString().trim()
        if (q.isEmpty() && a.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_empty_question), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val targetId = currentHistoryId ?: run {
                val saved = withContext(Dispatchers.IO) { historyManager.save(q, a) }
                currentHistoryId = saved.id
                saved.id
            }
            val newState = withContext(Dispatchers.IO) {
                historyManager.toggleBookmark(targetId)
            }
            updateBookmarkIcon(newState)
            Toast.makeText(
                this@MainActivity,
                if (newState) "Bookmark ho gaya" else "Bookmark hataya",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateBookmarkIcon(bookmarked: Boolean) {
        btnBookmark.setImageResource(
            if (bookmarked) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
    }

    // ---------- TTS ----------

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    var res = engine.setLanguage(Locale("hi", "IN"))
                    if (res == TextToSpeech.LANG_MISSING_DATA ||
                        res == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        res = engine.setLanguage(Locale.ENGLISH)
                    }
                    ttsReady = res != TextToSpeech.LANG_MISSING_DATA &&
                        res != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ttsReady) {
                        btnSpeak.isEnabled = false
                        Toast.makeText(
                            this,
                            "TTS voice available nahi",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    ttsReady = false
                    btnSpeak.isEnabled = false
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "TTS init failed", e)
            ttsReady = false
        }
    }

    private fun toggleSpeak() {
        val engine = tts
        if (!ttsReady || engine == null) {
            Toast.makeText(this, getString(R.string.err_tts), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (engine.isSpeaking) {
                engine.stop()
            } else {
                val text = tvAnswer.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Pehle koi jawab generate karo",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "padhai_answer")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "TTS speak failed", e)
        }
    }

    // ---------- Share ----------

    private fun shareAnswer() {
        val q = etQuestion.text.toString().trim()
        val a = tvAnswer.text.toString().trim()
        if (q.isEmpty() || a.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_no_answer), Toast.LENGTH_SHORT).show()
            return
        }
        val text = "Sawal: $q\n\nJawab: $a\n\n— Offline PadhAI (100% offline AI tutor)"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Offline PadhAI")
        }
        startActivity(Intent.createChooser(intent, "Share karo"))
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
        btnHistory = findViewById(R.id.btnHistory)
        btnQuiz = findViewById(R.id.btnQuiz)
        bottomSheet = findViewById(R.id.bottomSheet)
        tvSheetHint = findViewById(R.id.tvSheetHint)
        progressOcr = findViewById(R.id.progressOcr)
        inputContent = findViewById(R.id.inputContent)
        resultContent = findViewById(R.id.resultContent)
        etQuestion = findViewById(R.id.etQuestion)
        btnExplain = findViewById(R.id.btnExplain)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        setupLanguageSpinner()
        rowThinking = findViewById(R.id.rowThinking)
        tvAnswer = findViewById(R.id.tvAnswer)
        rowAnswerActions = findViewById(R.id.rowAnswerActions)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnShare = findViewById(R.id.btnShare)
        dividerFollow = findViewById(R.id.dividerFollow)
        tvFollowLabel = findViewById(R.id.tvFollowLabel)
        followRow = findViewById(R.id.followRow)
        etFollowUp = findViewById(R.id.etFollowUp)
        btnSend = findViewById(R.id.btnSend)
        cardSetup = findViewById(R.id.cardSetup)
        btnRecheck = findViewById(R.id.btnRecheck)
        tvSetupBody = findViewById(R.id.tvSetupBody)
        rgModels = findViewById(R.id.rgModels)
        rbModelLite = findViewById(R.id.rbModelLite)
        rbModelStandard = findViewById(R.id.rbModelStandard)
        rbModelPro = findViewById(R.id.rbModelPro)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        pbDownload = findViewById(R.id.pbDownload)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)
    }

    /** Bhasha dropdown: user jis bhasha me chahe jawab paye. Choice save rehti hai. */
    private var spinnerInitDone = false

    private fun setupLanguageSpinner() {
        val names = Languages.ALL.map { it.displayName }
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, names
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerLanguage.adapter = adapter
        val current = AppPrefs.getLanguage(this)
        spinnerLanguage.setSelection(
            Languages.ALL.indexOfFirst { it.code == current.code }.coerceAtLeast(0),
            false
        )
        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val code = Languages.ALL[position].code
                AppPrefs.setLanguage(this@MainActivity, code)
                // Init ke dauraan nahi — sirf user ke badalne pe UI bhasha lagao.
                if (spinnerInitDone) applyUiLocale(code)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerInitDone = true
        // App khulne pe saved bhasha ka UI locale lagao.
        applyUiLocale(current.code)
    }

    /**
     * UI ke buttons/labels bhi chuni hui bhasha me.
     * Hindi -> hi, English -> en, baaki (Hinglish default) -> default resources.
     */
    private fun applyUiLocale(code: String) {
        val tags = when (code) {
            "hindi" -> "hi"
            "english" -> "en"
            else -> ""
        }
        val newLocales = if (tags.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tags)
        }
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() != newLocales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(newLocales)
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w("MainActivity", "TTS shutdown failed", e)
        }
        modelDownloader?.detach()
        cameraManager.shutdown()
        tutor.close()
        super.onDestroy()
    }
}
