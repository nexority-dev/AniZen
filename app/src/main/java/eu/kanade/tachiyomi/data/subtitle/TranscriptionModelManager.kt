package eu.kanade.tachiyomi.data.subtitle

import android.content.Context
import androidx.work.WorkInfo
import eu.kanade.tachiyomi.data.subtitle.model.TranscriptionModelCatalog
import eu.kanade.tachiyomi.data.subtitle.model.TranscriptionModelId
import eu.kanade.tachiyomi.data.subtitle.model.TranscriptionModelInstallStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class TranscriptionModelManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<TranscriptionModelId, Job>()
    private val activeSteps = mutableMapOf<TranscriptionModelId, MutableStateFlow<TranscriptionModelInstallStep>>()

    private val modelsStateMapFlow = MutableStateFlow(emptyMap<TranscriptionModelId, TranscriptionModelInstallStep>())
    val modelsStateFlow: StateFlow<Map<TranscriptionModelId, TranscriptionModelInstallStep>> =
        modelsStateMapFlow.asStateFlow()

    init {
        scope.launch {
            TranscriptionModelCatalog.models.forEach { model ->
                modelsStateMapFlow.update { it + (model.id to stateOnDisk(model.id)) }
            }
        }
    }

    fun directoryFor(id: TranscriptionModelId): File {
        val base = context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)
        return File(base, id.name).apply { mkdirs() }
    }

    fun isDownloaded(id: TranscriptionModelId): Boolean = stateOnDisk(id) == TranscriptionModelInstallStep.Downloaded

    fun downloadModel(id: TranscriptionModelId): Flow<TranscriptionModelInstallStep> {
        activeJobs.remove(id)?.cancel()

        val step = MutableStateFlow(TranscriptionModelInstallStep.Pending)
        activeSteps[id] = step
        updateAggregate(id, TranscriptionModelInstallStep.Pending)

        TranscriptionModelDownloadJob.start(context, id)

        val job = scope.launch {
            TranscriptionModelDownloadJob.workInfosFlow(context, id).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val newStep = info.state.toInstallStep()
                step.value = newStep
                updateAggregate(id, newStep)
            }
        }
        activeJobs[id] = job

        return step.asStateFlow()
            .onCompletion {
                activeJobs.remove(id)
                activeSteps.remove(id)
                job.cancel()
            }
    }

    fun deleteModel(id: TranscriptionModelId): Boolean {
        activeJobs.remove(id)?.cancel()
        activeSteps.remove(id)
        TranscriptionModelDownloadJob.stop(context, id)

        val deleted = directoryFor(id).deleteRecursively()
        updateAggregate(id, TranscriptionModelInstallStep.Idle)
        return deleted
    }

    private fun stateOnDisk(id: TranscriptionModelId): TranscriptionModelInstallStep {
        val model = TranscriptionModelCatalog.get(id)
        val dir = directoryFor(id)
        val downloaded = model.artifacts.isNotEmpty() && model.artifacts.all { File(dir, it.fileName).exists() }
        return if (downloaded) TranscriptionModelInstallStep.Downloaded else TranscriptionModelInstallStep.Idle
    }

    private fun updateAggregate(id: TranscriptionModelId, step: TranscriptionModelInstallStep) {
        modelsStateMapFlow.update { it + (id to step) }
    }

    private fun WorkInfo.State.toInstallStep(): TranscriptionModelInstallStep = when (this) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TranscriptionModelInstallStep.Pending
        WorkInfo.State.RUNNING -> TranscriptionModelInstallStep.Downloading
        WorkInfo.State.SUCCEEDED -> TranscriptionModelInstallStep.Downloaded
        WorkInfo.State.FAILED -> TranscriptionModelInstallStep.Error
        WorkInfo.State.CANCELLED -> TranscriptionModelInstallStep.Idle
    }

    companion object {
        private const val MODELS_DIR = "transcription_models"
    }
}
