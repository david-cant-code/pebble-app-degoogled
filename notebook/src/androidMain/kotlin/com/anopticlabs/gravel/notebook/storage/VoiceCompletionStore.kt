package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Parked completion claims in `voice_completions`, oldest first, each waiting for the user to
 * pick the reminder it completes. Capped: past [MAX_COMPLETIONS] the oldest by insertion is
 * dropped.
 */
class VoiceCompletionStore(root: File, crypto: Cryptographer) {

    private val list = EncryptedJsonFile.list(
        EncryptedFiles(root, crypto),
        File(root, FILE_NAME),
        VoiceCompletion.serializer(),
        EncryptedJsonFile.SMALL_FILE_BYTES,
        ::requireWellFormed,
    )

    private val writes = MutableStateFlow(0L)

    /** Counts the saves and takes this store has made, so a screen showing the claims can read them again after one. */
    val revision: StateFlow<Long> = writes.asStateFlow()

    fun readAll(strict: Boolean = false): List<VoiceCompletion> = list.read(strict)

    /** Sets a damaged file aside for an empty one; see [EncryptedJsonFile.setAsideIfDamaged]. */
    suspend fun setAsideIfDamaged(): Boolean = list.setAsideIfDamaged()

    /** Null covers a failed read as well as an absent id unless [strict]. */
    fun get(completionId: String, strict: Boolean = false): VoiceCompletion? = readAll(strict).firstOrNull { it.completionId == completionId }

    /** Adds [completion] at the tail, dropping any earlier entry with its id, and returns the ids dropped to stay under the cap. */
    suspend fun save(completion: VoiceCompletion): List<String> {
        require(isValidStoreId(completion.completionId)) { "not a completion id: ${completion.completionId}" }
        return list.modify { current ->
            val updated = current.filterNot { it.completionId == completion.completionId } + completion
            val dropped = updated.dropLast(MAX_COMPLETIONS).map { it.completionId }
            if (dropped.isNotEmpty()) logger.w { "voice completions over $MAX_COMPLETIONS; dropping ${dropped.size} oldest" }
            Modified(updated.takeLast(MAX_COMPLETIONS), dropped)
        }.also { writes.update { it + 1 } }
    }

    suspend fun remove(completionId: String): Boolean = take(completionId) != null

    /** Removes and returns the entry under [completionId] in one lock section, so it is handed out once. */
    suspend fun take(completionId: String): VoiceCompletion? = list.modify { current ->
        Modified(current.filterNot { it.completionId == completionId }, current.firstOrNull { it.completionId == completionId })
    }.also { writes.update { it + 1 } }

    companion object {
        const val FILE_NAME = "voice_completions"
        const val MAX_COMPLETIONS = 50

        private fun requireWellFormed(completions: List<VoiceCompletion>) {
            require(completions.all { isValidStoreId(it.completionId) }) { "completion with an invalid id" }
            require(completions.distinctBy { it.completionId }.size == completions.size) { "completions share an id" }
            require(completions.size <= MAX_COMPLETIONS) { "more than $MAX_COMPLETIONS completions" }
        }

        private val logger = Logger.withTag("NotebookStorage")
    }
}
