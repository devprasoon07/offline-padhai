package com.devprasoon.offlinepadhai

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
            val result = tutor.generateQuiz(topic)
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
        topicScreen.visibility = View.GONE
        quizScreen.visibility = View.VISIBLE
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
        if (selectedOption == questions[currentIndex].answer) score++
        currentIndex++
        if (currentIndex >= questions.size) {
            showScore()
        } else {
            showQuestion()
        }
    }

    private fun showScore() {
        quizScreen.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.quiz_done_title))
            .setMessage(getString(R.string.quiz_score_msg, questions.size, score))
            .setPositiveButton(getString(R.string.quiz_again)) { _, _ ->
                topicScreen.visibility = View.VISIBLE
                etTopic.text?.clear()
                showStatus("")
            }
            .setNegativeButton(getString(R.string.quiz_close)) { _, _ -> finish() }
            .setCancelable(false)
            .show()
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
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
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
    }

    override fun onDestroy() {
        tutor.close()
        super.onDestroy()
    }
}
