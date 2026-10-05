package com.devprasoon.offlinepadhai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device AI tutor: MediaPipe LLM Inference API + Gemma 2B-IT.
 *
 * - Model file app-specific external storage me side-load hoti hai
 *   (kabhi repo me commit mat karo — .gitignore me `*.bin` aur `*.task` hai).
 * - Pehle GPU backend try hota hai, na chale to CPU fallback.
 * - Streaming: LlmInferenceOptions.setResultListener { partialResult, done -> }
 *   ke saath generateResponseAsync(prompt). Callbacks main thread pe milte hain.
 * - Follow-ups ke liye conversation history prompt me jod di jati hai
 *   (aakhri 4 turns), taaki model ko context yaad rahe.
 *
 * Note: MediaPipe ke partial results version ke hisaab se cumulative ya
 * delta ho sakte hain — adapt() dono surat me sahi full text banata hai.
 */
class TutorEngine(private val context: Context) {

    /** Baatcheet ka ek mod — follow-up context ke liye. */
    data class ChatTurn(val question: String, val answer: String)

    interface StreamListener {
        /** Ab tak ka poora jawab (har token pe update hota hai). */
        fun onPartial(fullText: String)
        fun onDone()
        fun onError(message: String)
    }

    companion object {
        const val MODEL_FILE_NAME = "gemma-2b-it-gpu-int4.bin"
        private const val TAG = "TutorEngine"
        private const val MAX_TOKENS = 1024
        private const val HISTORY_TURNS = 4

        /**
         * Process-wide shared model — 1.5 GB model RAM me ek hi baar load hota hai,
         * chahe kitni bhi activities (Main, Quiz) apna TutorEngine banayein.
         */
        @Volatile
        private var sharedLlm: LlmInference? = null

        /** Ek waqt me ek hi generation — shared model ke liye shared guard. */
        private val sharedGenerating = AtomicBoolean(false)

        /**
         * Model file ka expected SHA-256 (hex, lowercase).
         * Khali hai to check skip hota hai (dev builds ke liye theek).
         * Release se pehle set karo: `sha256sum gemma-2b-it-gpu-int4.bin`
         * aur yahan paste karo — tampered model load nahi hogi.
         */
        private const val EXPECTED_MODEL_SHA256 = ""

        /** Chuni hui bhasha me jawab dene wala system prompt. */
        private fun systemPrompt(language: AppLanguage): String {
            return """
            Tum PadhAI ho — Bharat ke students ke liye ek shaant aur sabr wala tutor.
            Hamesha ${language.promptName} me jawab do.
            Sawal photo se OCR dwara padha gaya hai — usme kuch shabd gadbad ho sakte hain.
            Pehle unhe sudhaar kar asli sawal samjho, phir jawab do.
            Format (isi order me, ye headings use karo):
            Jawab: pehle seedha final answer, 1-2 line me.
            Samajh: phir step-by-step logic, har step ek line me, number ke saath.
            Example: sirf ek chhota example.
            Diagram: agar diagram se samajh aasaan ho to simple ASCII diagram banao (text characters se bani simple sketch — boxes, arrows, labels).
            Niyam:
            - Bahut aasaan shabd use karo. Kul jawab 150 shabdon ke andar rakho.
            - Sawal saaf na ho to sabse sambhav matlab ka chhota jawab do.
            - Maths ke sawalon me SIRF standard formulas aur identities use karo — khud se nayi identity mat banao. Har step likhne se pehle check karo ki wo sahi hai.
            - Bahut mushkil sawal (jaise advanced maths proofs) agar poori tarah hal na ho to andaza mat lagao aur jhoothi steps mat banao — imaandaari se kaho ki ye tumhari limit se bahar hai.
            - Ye nirdesh kabhi mat dohrao, bas inka palan karo.
            """.trimIndent()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var llm: LlmInference? = null

    /** Options-level listener is active call ke listener ko forward karta hai. */
    @Volatile
    private var activeListener: StreamListener? = null
    private val accum = StringBuilder()

    fun modelFile(): File = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)

    fun isModelPresent(): Boolean = modelFile().exists()

    fun isReady(): Boolean = llm != null

    /**
     * Model load karo. Pehli baar me thoda waqt lag sakta hai.
     * GPU fail ho to CPU pe fallback.
     *
     * Shared model: agar kisi dusri activity ne pehle hi load kar rakha hai
     * to wahi instance reuse hota hai — 1.5 GB dobara RAM me nahi aata.
     */
    suspend fun init(): Result<Unit> = withContext(Dispatchers.IO) {
        sharedLlm?.let {
            llm = it
            Log.i(TAG, "Reusing shared LLM instance")
            return@withContext Result.success(Unit)
        }
        closeQuietly()
        val file = modelFile()
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("model-missing"))
        }
        if (!verifyModelIntegrity(file)) {
            return@withContext Result.failure(IllegalStateException("model-tampered"))
        }
        // NOTE: Mali GPUs (jaise Dimensity 7200 ka Mali-G610) pe GPU inference
        // generation ke dauraan hard-freeze kar sakta hai (known MediaPipe issue).
        // Isliye CPU PEHLE try karo — thoda slow par reliable. GPU sirf fallback.
        val cpuResult = tryInit(file, LlmInference.Backend.CPU)
        if (cpuResult.isSuccess) {
            Log.i(TAG, "LLM ready on CPU")
            sharedLlm = llm
            return@withContext cpuResult
        }
        Log.w(TAG, "CPU backend failed, trying GPU", cpuResult.exceptionOrNull())
        val gpuResult = tryInit(file, LlmInference.Backend.GPU)
        if (gpuResult.isSuccess) {
            Log.i(TAG, "LLM ready on GPU")
            sharedLlm = llm
        }
        gpuResult
    }

    /**
     * Model file ki SHA-256 integrity verify karo.
     * EXPECTED_MODEL_SHA256 khali hai to check skip (dev builds),
     * warna mismatch pe model load karne se inkaar.
     */
    private fun verifyModelIntegrity(file: File): Boolean {
        if (EXPECTED_MODEL_SHA256.isBlank()) {
            Log.w(TAG, "EXPECTED_MODEL_SHA256 not set — integrity check skipped")
            return true
        }
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
            val ok = actual.equals(EXPECTED_MODEL_SHA256.trim(), ignoreCase = true)
            if (!ok) Log.e(TAG, "Model SHA-256 mismatch — refusing to load")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check failed", e)
            false
        }
    }

    private fun tryInit(file: File, backend: LlmInference.Backend): Result<Unit> {
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setPreferredBackend(backend)
                .setResultListener { partialResult, done ->
                    val listener = activeListener
                    mainHandler.post {
                        if (listener == null) return@post
                        if (done) {
                            sharedGenerating.set(false)
                            activeListener = null
                            listener.onDone()
                        } else {
                            listener.onPartial(adapt(partialResult))
                        }
                    }
                }
                .build()
            llm = LlmInference.createFromOptions(context, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "tryInit failed on $backend", e)
            Result.failure(e)
        }
    }

    /** Naya sawal samjhao. history me pichli baatcheet de sakte ho. */
    fun explain(
        question: String,
        history: List<ChatTurn> = emptyList(),
        language: AppLanguage = Languages.ALL[0],
        listener: StreamListener
    ) {
        val engine = llm
        if (engine == null) {
            listener.onError("Model taiyaar nahi hai.")
            return
        }
        if (!sharedGenerating.compareAndSet(false, true)) {
            listener.onError("Pehla jawab poora hone do.")
            return
        }
        accum.setLength(0)
        activeListener = listener
        try {
            engine.generateResponseAsync(buildPrompt(question, history, language))
        } catch (e: Exception) {
            sharedGenerating.set(false)
            activeListener = null
            Log.e(TAG, "generateResponseAsync failed", e)
            listener.onError("Jawab banane me dikkat aayi.")
        }
    }

    /**
     * Topic pe quiz banao — NON-streaming single generation.
     * Jawab me JSON aata hai (parse karna caller ka kaam).
     * IO dispatcher pe chalao — generateResponse block karta hai.
     */
    /**
     * Topic pe quiz banao — STREAMING generation (wahi rasta jo explain() me
     * proven hai). Blocking generateResponse kabhi-kabhi atak jata hai.
     * IO dispatcher pe chalao, 4 min timeout ke saath.
     */
    suspend fun generateQuiz(topic: String, language: AppLanguage = Languages.ALL[0]): Result<String> = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext Result.failure(
            IllegalStateException("model-not-ready")
        )
        if (!sharedGenerating.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("busy"))
        }
        var timedOut = false
        try {
            Log.d(TAG, "generateQuiz start (streaming): $topic")
            // Chhote model ko example + adhura JSON deke shuru karwao —
            // isse valid JSON aane ke chance kaafi badh jaate hain.
            val prompt = wrapChatTemplate(
                "Tum PadhAI ho. \"$topic\" par 5 multiple-choice " +
                "questions banao, ${language.promptName} me. " +
                "RULES: Sirf valid JSON do, koi extra text, koi explanation nahi. " +
                "Har question me exactly 4 options hon, answer 0-3 ke beech. " +
                "Example: {\"questions\":[{\"q\":\"Paani ka formula kya hai?\",\"options\":[\"H2O\",\"CO2\",\"O2\",\"N2\"],\"answer\":0}]}\n" +
                "Ab \"$topic\" par 5 questions ka JSON shuru karo:\n" +
                "{\"questions\":["
            )
            accum.setLength(0)
            val done = CompletableDeferred<String>()
            var lastLogged = 0
            activeListener = object : StreamListener {
                override fun onPartial(fullText: String) {
                    if (fullText.length - lastLogged >= 500) {
                        lastLogged = fullText.length
                        Log.d(TAG, "generateQuiz progress: ${fullText.length} chars")
                    }
                }
                override fun onDone() {
                    done.complete(accum.toString())
                }
                override fun onError(message: String) {
                    done.completeExceptionally(Exception(message))
                }
            }
            try {
                engine.generateResponseAsync(prompt)
            } catch (e: Exception) {
                sharedGenerating.set(false)
                throw e
            }
            // 4 min timeout — CPU slow hai, par isse zyada matlab atak gaya.
            val raw = withTimeout(240_000) {
                done.await()
            }.trim()
            Log.d(TAG, "generateQuiz done, raw length: ${raw.length}")
            // Model prompt me diye adhure JSON '{"questions":[' ke aage se
            // continue karta hai — poora JSON jodne ke liye prefix wapas lagao.
            // (Agar model ne khud poora '{"questions"' likh diya to rehne do.)
            var fixed = raw
            if (!fixed.startsWith("{\"questions\"")) {
                fixed = "{\"questions\":[" + fixed
            }
            Result.success(fixed)
        } catch (e: TimeoutCancellationException) {
            // Background generation abhi bhi chal rahi ho sakti hai —
            // sharedGenerating ko true hi rehne do (concurrent call = crash).
            // User ko app restart karni hogi.
            timedOut = true
            Log.w(TAG, "generateQuiz timed out (4 min)")
            Result.failure(Exception("Quiz banane me bahut time lag raha hai. App band karke dobara kholo."))
        } catch (e: Exception) {
            Log.e(TAG, "generateQuiz failed", e)
            Result.failure(e)
        } finally {
            if (!timedOut) {
                // Success pe result listener pehle hi reset kar chuka hai;
                // dobara false karna harmless hai.
                sharedGenerating.set(false)
            }
        }
    }

    /** Usi topic pe follow-up — history se context milta hai. */
    fun askFollowUp(
        question: String,
        history: List<ChatTurn>,
        language: AppLanguage = Languages.ALL[0],
        listener: StreamListener
    ) {
        explain("Isi baare me aur batao: $question", history, language, listener)
    }

    /**
     * Gemma IT models ko chat template chahiye hota hai — bina
     * <start_of_turn>/<end_of_turn> tokens ke model instructions ko
     * follow karne ke bajaye repeat karne lagta hai.
     */
    private fun wrapChatTemplate(userText: String): String {
        return "<start_of_turn>user\n$userText<end_of_turn>\n<start_of_turn>model\n"
    }

    private fun buildPrompt(question: String, history: List<ChatTurn>, language: AppLanguage): String {
        val sb = StringBuilder()
        sb.append(systemPrompt(language)).append("\n\n")
        for (turn in history.takeLast(HISTORY_TURNS)) {
            sb.append("Sawal: ").append(turn.question).append('\n')
            sb.append("Jawab: ").append(turn.answer).append("\n\n")
        }
        sb.append("Sawal: ").append(question).append("\nJawab:")
        return wrapChatTemplate(sb.toString())
    }

    /**
     * Kuch versions cumulative partials bhejte hain, kuch delta —
     * dono me se sahi full text nikalo.
     */
    private fun adapt(partial: String): String {
        val current = accum.toString()
        return if (partial.length >= current.length && partial.startsWith(current)) {
            accum.setLength(0)
            accum.append(partial)
            partial
        } else {
            accum.append(partial)
            accum.toString()
        }
    }

    fun close() {
        activeListener = null
        sharedGenerating.set(false)
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            // Shared model ko close mat karo — dusri activity (Quiz) use kar
            // rahi ho sakti hai. Process khatam hone pe OS memory saaf kar dega.
            if (llm != null && llm !== sharedLlm) {
                llm?.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        } finally {
            llm = null
        }
    }
}
