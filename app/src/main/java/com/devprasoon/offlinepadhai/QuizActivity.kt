package com.devprasoon.offlinepadhai

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Quiz mode: topic do, AI 5 multiple-choice questions banata hai (Hinglish),
 * ek-ek karke jawab do, aakhir me score.
 *
 * Model shared hai (TutorEngine ka shared holder) — dobara load nahi hota.
 */
class QuizActivity : AppCompatActivity() {

    private data class QuizQuestion(
        val q: String,
        val options: List<String>,
        val answer: Int
    )

    private lateinit var tutor: TutorEngine

    private lateinit var topicScreen: LinearLayout
    private lateinit var etTopic: EditText
    private lateinit var btnMakeQuiz: Button
    private lateinit var progressQuiz: ProgressBar
    private lateinit var tvQuizStatus: TextView

    private lateinit var quizScreen: LinearLayout
    private lateinit var tvQNum: TextView
    private lateinit var tvQuizQuestion: TextView
    private lateinit var optionButtons: List<Button>
    private lateinit var btnNext: Button

    private var questions: List<QuizQuestion> = emptyList()
    private var currentIndex = 0
    private var selectedOption = -1
    private var score = 0
    private val userAnswers = mutableListOf<Int>()

    private lateinit var resultScreen: LinearLayout
    private lateinit var tvFinalScore: TextView
    private lateinit var resultContainer: LinearLayout
    private lateinit var btnQuizAgain: Button
    private lateinit var btnQuizClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)
        bindViews()

        tutor = TutorEngine(this)
        btnMakeQuiz.isEnabled = false
        lifecycleScope.launch {
            showStatus(getString(R.string.status_model_loading))
            val result = tutor.init()
            if (result.isSuccess) {
                showStatus("")
                btnMakeQuiz.isEnabled = true
            } else {
                showStatus(getString(R.string.err_model_load))
            }
        }

        btnMakeQuiz.setOnClickListener { makeQuiz() }
        btnNext.setOnClickListener { nextQuestion() }
        optionButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener { selectOption(idx) }
        }
    }

    private fun makeQuiz() {
        val topic = etTopic.text.toString().trim()
        if (topic.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_empty_topic), Toast.LENGTH_SHORT).show()
            return
        }
        btnMakeQuiz.isEnabled = false
        progressQuiz.visibility = View.VISIBLE
        showStatus(getString(R.string.quiz_making))

        lifecycleScope.launch {
            val result = tutor.generateQuiz(topic, AppPrefs.getLanguage(this@QuizActivity))
            progressQuiz.visibility = View.GONE
            btnMakeQuiz.isEnabled = true
            if (result.isSuccess) {
                val parsed = parseQuiz(result.getOrThrow())
                if (parsed.isEmpty()) {
                    showStatus("")
                    Toast.makeText(
                        this@QuizActivity,
                        getString(R.string.quiz_error),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    startQuiz(parsed)
                }
            } else {
                showStatus("")
                Toast.makeText(
                    this@QuizActivity,
                    getString(R.string.quiz_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startQuiz(qs: List<QuizQuestion>) {
        questions = qs
        currentIndex = 0
        score = 0
        userAnswers.clear()
        topicScreen.visibility = View.GONE
        quizScreen.visibility = View.VISIBLE
        resultScreen.visibility = View.GONE
        showQuestion()
    }

    private fun showQuestion() {
        val q = questions[currentIndex]
        tvQNum.text = getString(R.string.quiz_qnum, currentIndex + 1, questions.size)
        tvQuizQuestion.text = q.q
        selectedOption = -1
        optionButtons.forEachIndexed { idx, btn ->
            btn.text = q.options[idx]
            btn.alpha = 1f
        }
        btnNext.text = if (currentIndex == questions.size - 1) {
            getString(R.string.quiz_finish)
        } else {
            getString(R.string.quiz_next)
        }
    }

    private fun selectOption(idx: Int) {
        selectedOption = idx
        optionButtons.forEachIndexed { i, btn ->
            btn.alpha = if (i == idx) 1f else 0.45f
        }
    }

    private fun nextQuestion() {
        if (selectedOption == -1) {
            Toast.makeText(this, getString(R.string.quiz_pick_option), Toast.LENGTH_SHORT).show()
            return
        }
        userAnswers.add(selectedOption)
        if (selectedOption == questions[currentIndex].answer) score++
        currentIndex++
        if (currentIndex >= questions.size) {
            showResults()
        } else {
            showQuestion()
        }
    }

    /** Detailed results: score + har question ka sahi jawab. */
    private fun showResults() {
        quizScreen.visibility = View.GONE
        resultScreen.visibility = View.VISIBLE

        tvFinalScore.text = getString(R.string.quiz_score_msg, questions.size, score)

        resultContainer.removeAllViews()
        questions.forEachIndexed { idx, q ->
            val userAns = userAnswers.getOrNull(idx) ?: -1
            val isCorrect = userAns == q.answer

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 16
                layoutParams = params
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            }

            val qText = TextView(this).apply {
                text = "${idx + 1}. ${q.q}"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(qText)

            q.options.forEachIndexed { optIdx, opt ->
                val optText = TextView(this).apply {
                    val marker = when {
                        optIdx == q.answer -> "✓ "
                        optIdx == userAns && !isCorrect -> "✗ "
                        else -> "  "
                    }
                    text = "$marker$opt"
                    textSize = 14f
                    setPadding(0, 8, 0, 8)
                    setTextColor(
                        when {
                            optIdx == q.answer -> 0xFF2E7D32.toInt() // green
                            optIdx == userAns && !isCorrect -> 0xFFC62828.toInt() // red
                            else -> 0xFF666666.toInt()
                        }
                    )
                }
                card.addView(optText)
            }

            resultContainer.addView(card)
        }

        btnQuizAgain.setOnClickListener {
            resultScreen.visibility = View.GONE
            topicScreen.visibility = View.VISIBLE
            etTopic.text?.clear()
            showStatus("")
        }
        btnQuizClose.setOnClickListener { finish() }
    }

    private fun showStatus(msg: String) {
        tvQuizStatus.text = msg
        tvQuizStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Model ka jawab parse karo. Markdown fences (```json) hatao,
     * invalid questions skip karo. Zero valid → empty list.
     */
    private fun parseQuiz(raw: String): List<QuizQuestion> {
        val out = mutableListOf<QuizQuestion>()
        try {
            val json = extractJson(raw)
            val root = JSONObject(json)
            val arr = root.optJSONArray("questions") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optString("q", "").trim()
                val optsArr = o.optJSONArray("options") ?: continue
                if (q.isEmpty() || optsArr.length() != 4) continue
                val opts = (0 until 4).map { optsArr.optString(it, "").trim() }
                if (opts.any { it.isEmpty() }) continue
                val ans = o.optInt("answer", -1)
                if (ans !in 0..3) continue
                out.add(QuizQuestion(q, opts, ans))
            }
        } catch (e: Exception) {
            // parse fail → empty list, caller Toast dikhayega
        }
        return out
    }

    /** Bahar ke extra text/fences hata ke sabse bada {...} block nikalo. */
    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return raw
        var json = raw.substring(start, end + 1)
        // Chhote models aksar trailing commas chhod dete hain — saaf karo.
        json = json.replace(Regex(",\\s*([}\\]])"), "$1")
        return json
    }

    private fun bindViews() {
        topicScreen = findViewById(R.id.topicScreen)
        etTopic = findViewById(R.id.etTopic)
        btnMakeQuiz = findViewById(R.id.btnMakeQuiz)
        progressQuiz = findViewById(R.id.progressQuiz)
        tvQuizStatus = findViewById(R.id.tvQuizStatus)
        quizScreen = findViewById(R.id.quizScreen)
        tvQNum = findViewById(R.id.tvQNum)
        tvQuizQuestion = findViewById(R.id.tvQuizQuestion)
        optionButtons = listOf(
            findViewById(R.id.opt0),
            findViewById(R.id.opt1),
            findViewById(R.id.opt2),
            findViewById(R.id.opt3)
        )
        btnNext = findViewById(R.id.btnNext)
        resultScreen = findViewById(R.id.resultScreen)
        tvFinalScore = findViewById(R.id.tvFinalScore)
        resultContainer = findViewById(R.id.resultContainer)
        btnQuizAgain = findViewById(R.id.btnQuizAgain)
        btnQuizClose = findViewById(R.id.btnQuizClose)
    }

    override fun onDestroy() {
        tutor.close()
        super.onDestroy()
    }
}
