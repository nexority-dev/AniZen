package eu.kanade.tachiyomi.data.subtitle.model

enum class TranscriptionModelId {
    SMALL_EN,
    DISTIL_MEDIUM_EN,
}

data class ModelArtifact(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
)

data class TranscriptionModel(
    val id: TranscriptionModelId,
    val displayName: String,
    val artifacts: List<ModelArtifact>,
) {
    val totalSizeBytes: Long
        get() = artifacts.sumOf { it.sizeBytes }
}

object TranscriptionModelCatalog {

    val models: List<TranscriptionModel> = listOf(
        TranscriptionModel(
            id = TranscriptionModelId.SMALL_EN,
            displayName = "small.en",
            artifacts = listOf(
                ModelArtifact(fileName = "small.en.onnx", url = "", sizeBytes = 0L),
            ),
        ),
        TranscriptionModel(
            id = TranscriptionModelId.DISTIL_MEDIUM_EN,
            displayName = "distil-medium.en",
            artifacts = listOf(
                ModelArtifact(fileName = "distil-medium.en.onnx", url = "", sizeBytes = 0L),
            ),
        ),
    )

    fun get(id: TranscriptionModelId): TranscriptionModel = models.first { it.id == id }
}

enum class TranscriptionModelInstallStep {
    Idle,
    Pending,
    Downloading,
    Downloaded,
    Error,
    ;

    fun isCompleted(): Boolean = this == Downloaded || this == Error || this == Idle
}
