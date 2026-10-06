package com.devprasoon.offlinepadhai

/**
 * Download/lode hone wale AI models ki catalog.
 *
 * Teen approved tiers (Dev, 2026-10-05):
 * - Lite: Gemma 3 1B (~1 GB) — halke/weak phones ke liye
 * - Standard: Gemma 2B-IT (~1.5 GB) — default, santulan
 * - Pro: Qwen 2.5 (~1.9 GB) — best quality, maths/reasoning
 *   (DeepSeek R1 future option hai — uske <think> tags ke liye
 *   alag handling chahiye hogi, isliye Pro me Qwen 2.5 rakha hai.)
 *
 * downloadUrl: seedha HTTPS link jo MediaPipe-compatible .bin/.task file de.
 * Khali hai to wo model abhi download ke liye configure nahi hua —
 * Dev ko link dena hoga (Gemma models ke liye Kaggle ToS accept karna
 * zaroori hai, isliye unke link Dev khud dega).
 */
enum class ChatTemplate { GEMMA, CHATML }

data class AIModel(
    val id: String,
    val displayName: String,
    val modelName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sizeLabel: String,
    val downloadUrl: String,
    val sha256: String,
    val blurb: String,
    val chatTemplate: ChatTemplate,
    val recommended: Boolean = false
)

object ModelCatalog {
    val MODELS = listOf(
        AIModel(
            id = "lite",
            displayName = "Lite",
            modelName = "Gemma 3 1B",
            fileName = "gemma-3-1b-it-int4.bin",
            sizeBytes = 1_000_000_000L,
            sizeLabel = "~1 GB",
            downloadUrl = "",
            sha256 = "",
            blurb = "Halke phones ke liye — tez, kam RAM khata hai",
            chatTemplate = ChatTemplate.GEMMA
        ),
        AIModel(
            id = "standard",
            displayName = "Standard",
            modelName = "Gemma 2B-IT",
            fileName = "gemma-2b-it-gpu-int4.bin",
            sizeBytes = 1_500_000_000L,
            sizeLabel = "~1.5 GB",
            downloadUrl = "",
            sha256 = "",
            blurb = "Santulan — quality aur speed (default)",
            chatTemplate = ChatTemplate.GEMMA,
            recommended = true
        ),
        AIModel(
            id = "pro",
            displayName = "Pro",
            modelName = "Qwen 2.5",
            fileName = "qwen2p5-int4.bin",
            sizeBytes = 1_900_000_000L,
            sizeLabel = "~1.9 GB",
            downloadUrl = "",
            sha256 = "",
            blurb = "Best quality — maths aur reasoning ke liye",
            chatTemplate = ChatTemplate.CHATML
        )
    )

    fun byId(id: String?): AIModel =
        MODELS.find { it.id == id } ?: default()

    fun default(): AIModel = MODELS.first { it.recommended }
}
