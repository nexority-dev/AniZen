package eu.kanade.tachiyomi.data.subtitle

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify

internal class TranscriptionModelNotifier(private val context: Context) {

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_TRANSCRIPTION_MODEL)

    private fun NotificationCompat.Builder.show(id: Int = Notifications.ID_TRANSCRIPTION_MODEL_PROGRESS) {
        context.notify(id, build())
    }

    fun cancel() {
        NotificationReceiver.dismissNotification(context, Notifications.ID_TRANSCRIPTION_MODEL_PROGRESS)
    }

    fun onDownloadStarted(title: String? = null): NotificationCompat.Builder {
        with(notificationBuilder) {
            title?.let { setContentTitle(it) }
            setContentText("Downloading")
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
            clearActions()
        }
        notificationBuilder.show()
        return notificationBuilder
    }

    fun onProgressChange(title: String, artifactIndex: Int, artifactCount: Int, progress: Int) {
        with(notificationBuilder) {
            setContentTitle(title)
            setContentText(
                if (artifactCount > 1) "Downloading ($artifactIndex/$artifactCount)" else "Downloading",
            )
            setProgress(100, progress, false)
            setOnlyAlertOnce(true)
        }
        notificationBuilder.show()
    }

    fun onDownloadError(title: String?, error: String?) {
        with(notificationBuilder) {
            title?.let { setContentTitle(it) }
            setContentText(error ?: "Download failed")
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setOngoing(false)
            setOnlyAlertOnce(false)
            setProgress(0, 0, false)
            clearActions()
        }
        notificationBuilder.show(Notifications.ID_TRANSCRIPTION_MODEL_ERROR)
    }
}
