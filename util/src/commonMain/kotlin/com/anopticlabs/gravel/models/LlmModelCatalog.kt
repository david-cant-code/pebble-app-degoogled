package com.anopticlabs.gravel.models

/** The chat layout the engine's built-in formatter produces for a model's template. */
enum class ChatFormat { ChatMl }

/**
 * @param promptSuffix appended to the formatted conversation before
 *   generation, for what the template adds that the formatter cannot.
 */
data class LlmModel(
    override val id: String,
    override val displayName: String,
    override val fileName: String,
    override val sha256: String,
    override val sizeBytes: Long,
    override val minRamBytes: Long,
    override val license: String,
    override val repo: String,
    override val commit: String,
    val chatFormat: ChatFormat,
    val promptSuffix: String,
) : PinnedModel {
    override val role: ModelRole get() = ModelRole.Text
}

/**
 * The Notebook's text models, pinned like the speech models (re-pin
 * procedure on `WhisperModelCatalog`); both are unsloth's quantizations.
 */
object LlmModelCatalog {
    private const val GIB = 1024L * 1024L * 1024L

    /**
     * The empty reasoning block both pinned files' embedded templates emit
     * after the assistant header when thinking is off. The engine's ChatML
     * formatter stops at the header (llama.cpp v0.4.1, `src/llama-chat.cpp`,
     * `llm_chat_apply_template`, the `LLM_CHAT_TEMPLATE_CHATML` branch).
     */
    const val THINK_SUPPRESS = "<think>\n\n</think>\n\n"

    // Values derived 2026-09-22 via the three-source procedure. Largest first (recommendedTextModel).
    val MODELS: List<LlmModel> = listOf(
        LlmModel(
            id = "qwen3-1_7b-q4_k_m",
            displayName = "Qwen3 1.7B (Q4_K_M)",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
            sizeBytes = 1_107_409_472,
            minRamBytes = 3 * GIB,
            license = "Apache-2.0",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            commit = "d7f544eead698dbd1f15126ef60b45a1e1933222",
            chatFormat = ChatFormat.ChatMl,
            promptSuffix = THINK_SUPPRESS,
        ),
        LlmModel(
            id = "qwen3_5-0_8b-q5_k_m",
            displayName = "Qwen3.5 0.8B (Q5_K_M)",
            fileName = "Qwen3.5-0.8B-Q5_K_M.gguf",
            sha256 = "c3ef5827b322c4be08a3a26ce424460d8b37daf593356c2cc1f4e40a7ab0581b",
            sizeBytes = 590_057_728,
            minRamBytes = 3 * GIB,
            license = "Apache-2.0",
            repo = "unsloth/Qwen3.5-0.8B-GGUF",
            commit = "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
            chatFormat = ChatFormat.ChatMl,
            promptSuffix = THINK_SUPPRESS,
        ),
    )

    fun byId(id: String): LlmModel? = MODELS.firstOrNull { it.id == id }
}
