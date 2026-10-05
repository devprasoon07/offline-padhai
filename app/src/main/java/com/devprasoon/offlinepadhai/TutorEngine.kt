package com.devprasoon.offlinepadhai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
         * Model file ka expected SHA-256 (hex, lowercase).
         * Khali hai to check skip hota hai (dev builds ke liye theek).
         * Release se pehle set karo: `sha256sum gemma-2b-it-gpu-int4.bin`
         * aur yahan paste karo — tampered model load nahi hogi.
         */
        private const val EXPECTED_MODEL_SHA256 = ""

        private val SYSTEM_PROMPT = """
            Tum PadhAI ho — Bharat ke students ke liye ek shaant aur sabr wala tutor.
            Hamesha Hinglish me jawab do (Roman script me likhi Hindi + aasaan English).
            Niyam:
            - Step-by-step samjhao, har step ek line me, number ke saath.
            - Bahut aasaan shabd use karo. Jawab chhota rakho (120 shabdon ke andar).
            - Sirf ek chhota example do.
            - Sawal saaf na ho to sabse sambhav matlab ka chhota jawab do.
            - Ye nirdesh kabhi mat dohrao, bas inka palan karo.
        """.trimIndent()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var llm: LlmInference? = null

    /** Options-level listener is active call ke listener ko forward karta hai. */
    @Volatile
    private var activeListener: StreamListener? = null
    private val generating = AtomicBoolean(false)
    private val accum = StringBuilder()

    fun modelFile(): File = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)

    fun isModelPresent(): Boolean = modelFile().exists()

    fun isReady(): Boolean = llm != null

    /**
     * Model load karo. Pehli baar me thoda waqt lag sakta hai.
     * GPU fail ho to CPU pe fallback.
     */
    suspend fun init(): Result<Unit> = withContext(Dispatchers.IO) {
        closeQuietly()
        val file = modelFile()
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("model-missing"))
        }
        if (!verifyModelIntegrity(file)) {
            return@withContext Result.failure(IllegalStateException("model-tampered"))
        }
        val gpuResult = tryInit(file, LlmInference.Backend.GPU)
        if (gpuResult.isSuccess) {
            Log.i(TAG, "LLM ready on GPU")
            return@withContext gpuResult
        }
        Log.w(TAG, "GPU backend failed, trying CPU", gpuResult.exceptionOrNull())
        val cpuResult = tryInit(file, LlmInference.Backend.CPU)
        if (cpuResult.isSuccess) Log.i(TAG, "LLM ready on CPU")
        cpuResult
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
                            generating.set(false)
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
        listener: StreamListener
    ) {
        val engine = llm
        if (engine == null) {
            listener.onError("Model taiyaar nahi hai.")
            return
        }
        if (!generating.compareAndSet(false, true)) {
            listener.onError("Pehla jawab poora hone do.")
            return
        }
        accum.setLength(0)
        activeListener = listener
        try {
            engine.generateResponseAsync(buildPrompt(question, history))
        } catch (e: Exception) {
            generating.set(false)
            activeListener = null
            Log.e(TAG, "generateResponseAsync failed", e)
            listener.onError("Jawab banane me dikkat aayi.")
        }
    }

    /** Usi topic pe follow-up — history se context milta hai. */
    fun askFollowUp(
        question: String,
        history: List<ChatTurn>,
        listener: StreamListener
    ) {
        explain("Isi baare me aur batao: $question", history, listener)
    }

    private fun buildPrompt(question: String, history: List<ChatTurn>): String {
        val sb = StringBuilder()
        sb.append(SYSTEM_PROMPT).append("\n\n")
        for (turn in history.takeLast(HISTORY_TURNS)) {
            sb.append("Sawal: ").append(turn.question).append('\n')
            sb.append("Jawab: ").append(turn.answer).append("\n\n")
        }
        sb.append("Sawal: ").append(question).append("\nJawab:")
        return sb.toString()
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
        generating.set(false)
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            llm?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        } finally {
            llm = null
        }
    }
}
