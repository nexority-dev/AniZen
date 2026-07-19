package eu.kanade.tachiyomi.data.subtitle

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.subtitle.model.TranscriptionModel
import eu.kanade.tachiyomi.data.subtitle.model.TranscriptionModelCatalog
import eu.kanade.tachiyomi.data.subtitle.model.TranscriptionModelId
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

class TranscriptionModelDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = TranscriptionModelNotifier(context)
    private val network: NetworkHelper by injectLazy()
    private val manager: TranscriptionModelManager by injectLazy()

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(EXTRA_MODEL_ID)
            ?.let { runCatching { TranscriptionModelId.valueOf(it) }.getOrNull() }
            ?: return Result.failure()

        val model = TranscriptionModelCatalog.get(modelId)

        setForegroundSafely()

        return withIOContext {
            try {
                downloadModel(model)
                notifier.cancel()
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException || isStopped) {
                    notifier.cancel()
                } else {
                    notifier.onDownloadError(model.displayName, e.message)
                }
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_TRANSCRIPTION_MODEL_PROGRESS,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun downloadModel(model: TranscriptionModel) {
        notifier.onDownloadStarted(model.displayName)

        val destDir = manager.directoryFor(model.id)

        model.artifacts.forEachIndexed { index, artifact ->
            val tmpFile = File(destDir, "${artifact.fileName}.part")

            val progressListener = object : ProgressListener {
                var lastTick = 0L

                override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - 200 > lastTick) {
                        lastTick = currentTime
                        val progress = if (contentLength > 0) {
                            (100 * (bytesRead.toFloat() / contentLength)).toInt()
                        } else {
                            0
                        }
                        notifier.onProgressChange(model.displayName, index + 1, model.artifacts.size, progress)
                    }
                }
            }

            network.downloadFile(artifact.url, tmpFile, progressListener)

            if (isStopped) {
                tmpFile.delete()
                throw CancellationException("Transcription model download cancelled")
            }

            val finalFile = File(destDir, artifact.fileName)
            if (!tmpFile.renameTo(finalFile)) {
                throw IOException("Failed to finalize ${artifact.fileName}")
            }
        }
    }

    companion object {
        const val EXTRA_MODEL_ID = "TranscriptionModelDownloadJob.MODEL_ID"

        private fun workName(modelId: TranscriptionModelId) = "model_download_${modelId.name}"

        fun start(context: Context, modelId: TranscriptionModelId) {
            val data = Data.Builder().putString(EXTRA_MODEL_ID, modelId.name).build()
            val request = OneTimeWorkRequestBuilder<TranscriptionModelDownloadJob>()
                .setInputData(data)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context, modelId: TranscriptionModelId) {
            context.workManager.cancelUniqueWork(workName(modelId))
        }

        fun workInfosFlow(context: Context, modelId: TranscriptionModelId): Flow<List<WorkInfo>> =
            context.workManager.getWorkInfosForUniqueWorkFlow(workName(modelId))
    }
}
