package tf.monochrome.recorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tf.monochrome.recorder.editor.store.ProjectStore
import tf.monochrome.recorder.media.RecordingsRepository
import tf.monochrome.recorder.media.StorageSweep
import tf.monochrome.recorder.media.Thumbnails
import tf.monochrome.recorder.settings.SettingsStore

class RecorderApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var projects: ProjectStore
        private set
    lateinit var recordings: RecordingsRepository
        private set

    /**
     * Work that must finish whatever screen or service started it — saving a
     * recording, a bundle export — lives as long as the process. On main;
     * switch to IO inside for anything slow.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        val startedAtMs = System.currentTimeMillis()
        CrashLog.install(this)
        settings = SettingsStore(this)
        projects = ProjectStore(this)
        recordings = RecordingsRepository(this)
        createChannels()
        appScope.launch(Dispatchers.IO) { StorageSweep.run(this@RecorderApp, startedAtMs) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Thumbnails.trimMemory(level)
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
