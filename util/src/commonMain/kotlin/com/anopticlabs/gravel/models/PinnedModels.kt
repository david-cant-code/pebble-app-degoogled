package com.anopticlabs.gravel.models

import coredevices.util.models.WhisperModelCatalog

/** What a model is for; a feature lists, selects and loads only the role it serves. */
enum class ModelRole { Speech, Text }

/** One integrity-pinned model file, as the installer and the startup sweep see it. */
interface PinnedModel {
    /** Catalog identity and on-disk directory name; stable across releases. */
    val id: String
    val role: ModelRole
    val displayName: String
    val fileName: String
    val sha256: String
    val sizeBytes: Long
    /** Total-device-RAM floor for recommending the model, not a gate. */
    val minRamBytes: Long
    val license: String
    val repo: String
    /** The immutable commit of [repo] every download resolves. */
    val commit: String
}

object ModelCatalog {
    /** Weights under any other license are not offered. */
    val ALLOWED_LICENSES: Set<String> = setOf("MIT", "Apache-2.0")

    val MODELS: List<PinnedModel> = WhisperModelCatalog.MODELS + LlmModelCatalog.MODELS

    fun byId(id: String): PinnedModel? = MODELS.firstOrNull { it.id == id }

    fun urlFor(model: PinnedModel): String =
        "https://huggingface.co/${model.repo}/resolve/${model.commit}/${model.fileName}"
}

/**
 * Writing a model file failed on the device's own storage, where a retry
 * would fail the same way; [outOfSpace] when the free-space check refused it.
 */
class ModelStorageException(
    message: String,
    cause: Throwable? = null,
    val outOfSpace: Boolean = false,
) : Exception(message, cause)
