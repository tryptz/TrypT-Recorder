package tf.monochrome.recorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import tf.monochrome.recorder.editor.store.ProjectStore
import tf.monochrome.recorder.media.RecordingsRepository
import tf.monochrome.recorder.settings.SettingsStore

class RecorderApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var projects: ProjectStore
        private set
    lateinit var recordings: RecordingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        settings = SettingsStore(this)
        projects = ProjectStore(this)
        recordings = RecordingsRepository(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SAVED, getString(R.string.channel_saved), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EXPORT, getString(R.string.channel_export), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_SAVED = "saved"
        const val CHANNEL_EXPORT = "export"

        /** Output folders, under Movies/ and Music/ in shared storage. */
        const val FOLDER = "TryptifyRecorder"

        lateinit var instance: RecorderApp
            private set
    }
}
