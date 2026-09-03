package coredevices.util.transcription

/**
 * Path/lifecycle surface for the on-device speech models. The interface
 * keeps its upstream name (and the legacy [getSTTModelPath]/[getLMModelPath]
 * pair) to bound merge cost even though the engine behind it is now
 * whisper; [getModelPath] is the fork extension the multi-model catalog
 * needs, since the legacy getters bake in a single configured model each.
 */
interface CactusModelPathProvider {
    suspend fun getSTTModelPath(): String
    suspend fun getLMModelPath(): String

    /**
     * Resolve the model with catalog id [modelId], returning its file path.
     * When [allowReinstall] is true (the user-initiated download path) a
     * missing or load-verification-failed model is (re)downloaded through
     * the installer. When false (the engine init path) the resolution is
     * fail-closed: a missing model, or one that fails load-time
     * verification and is quarantined, throws instead of pulling a
     * multi-hundred-MB download outside the consented, notification-visible
     * download flow. The caller then treats the model as not installed and
     * lets the visible download UI re-fetch it.
     */
    suspend fun getModelPath(modelId: String, allowReinstall: Boolean = true): String

    fun isModelDownloaded(modelName: String): Boolean
    fun getDownloadedModels(): List<String>
    fun getIncompatibleModels(): List<String>
    fun deleteModel(modelName: String)
    fun getModelSizeBytes(modelName: String): Long
    fun initTelemetry()

    /**
     * Fork extension: the installed, load-verified path of the voice
     * activity detector, or null when it is absent or fails verification.
     * Never downloads; the engine runs without silence trimming when this
     * is null. Defaults to null so providers without a detector (test
     * fakes, previews) need no change.
     */
    suspend fun getVadModelPath(): String? = null

    /** Fork extension: whether the detector file is present in installed shape. */
    fun isVadModelInstalled(): Boolean = false
}
