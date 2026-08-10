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

    /** Resolve (installing if needed) the model with catalog id [modelId]. */
    suspend fun getModelPath(modelId: String): String

    fun isModelDownloaded(modelName: String): Boolean
    fun getDownloadedModels(): List<String>
    fun getIncompatibleModels(): List<String>
    fun deleteModel(modelName: String)
    fun getModelSizeBytes(modelName: String): Long
    fun initTelemetry()
}
