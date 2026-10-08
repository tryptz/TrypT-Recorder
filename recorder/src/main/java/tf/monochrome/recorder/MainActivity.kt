package tf.monochrome.recorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.recorder.capture.RecorderService
import tf.monochrome.recorder.capture.RecorderState
import tf.monochrome.recorder.editor.EditorLauncher
import tf.monochrome.recorder.editor.ui.EditorScreen
import tf.monochrome.recorder.ui.LibraryScreen
import tf.monochrome.recorder.ui.RecordScreen
import tf.monochrome.recorder.ui.RecorderTheme
import tf.monochrome.recorder.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    /** A video to open in the editor, from the "saved" notification. */
    private val pendingEdit = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { RecorderTheme { AppRoot(pendingEdit) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // The button does not survive the process; bring it back with the app.
        val wanted = RecorderApp.instance.settings.current.floatingButton
        if (wanted && Settings.canDrawOverlays(this) && !RecorderState.buttonShown.value) {
            RecorderService.showButton(this)
        }
    }

    /**
     * This activity is exported (it is the launcher), so the edit action is
     * validated: only a MediaStore item is opened, never an arbitrary
     * provider another app points us at.
     */
    private fun handle(intent: Intent?) {
        if (intent?.action != ACTION_EDIT_VIDEO) return
        val uri = intent.data ?: return
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) pendingEdit.value = uri
    }

    companion object {
        const val ACTION_EDIT_VIDEO = "tf.monochrome.recorder.EDIT_VIDEO"

        fun editIntent(context: Context, uri: Uri) = Intent(context, MainActivity::class.java)
            .setAction(ACTION_EDIT_VIDEO)
            .setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Composable
private fun AppRoot(pendingEdit: MutableStateFlow<Uri?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    val pending by pendingEdit.collectAsStateWithLifecycle()

    fun openUris(uris: List<Uri>) {
        scope.launch {
            opening = true
            val id = EditorLauncher.newProject(context, uris)
            opening = false
            if (id != null) editing = id else Toast.makeText(context, R.string.editor_some_files_skipped, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pending) {
        pending?.let {
            pendingEdit.value = null
            openUris(listOf(it))
        }
    }

    val current = editing
    if (current != null) {
        EditorScreen(projectId = current, onClose = {
            editing = null
            scope.launch { RecorderApp.instance.projects.refresh() }
        })
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.FiberManualRecord, null) },
                        label = { Text(stringResource(R.string.tab_record)) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.VideoLibrary, null) },
                        label = { Text(stringResource(R.string.tab_library)) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text(stringResource(R.string.tab_settings)) },
                    )
                }
            },
        ) { padding ->
            val m = Modifier.padding(padding)
            when (tab) {
                0 -> RecordScreen(onEdit = { openUris(listOf(it)) }, modifier = m)
                1 -> LibraryScreen(onOpenProject = { editing = it }, onEditUris = ::openUris, modifier = m)
                else -> SettingsScreen(modifier = m)
            }
        }
    }

    if (opening) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.opening_editor)) },
            text = { CircularProgressIndicator() },
        )
    }
}
