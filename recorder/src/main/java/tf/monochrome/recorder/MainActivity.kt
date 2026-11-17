package tf.monochrome.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.ui.glass.GlassPill
import tf.monochrome.recorder.ui.glass.LocalGlassBackdrop
import tf.monochrome.recorder.ui.glass.glassBackdrop
import tf.monochrome.recorder.ui.glass.rememberGlassBackdrop
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
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // The pages are the dock's backdrop: it is drawn beside them, never inside.
                val m = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = APP_DOCK_ROOM)
                Box(
                    Modifier
                        .fillMaxSize()
                        .glassBackdrop(backdrop)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    when (tab) {
                        0 -> RecordScreen(onEdit = { openUris(listOf(it)) }, modifier = m)
                        1 -> LibraryScreen(onOpenProject = { editing = it }, onEditUris = ::openUris, modifier = m)
                        else -> SettingsScreen(modifier = m)
                    }
                }
                AppDock(
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 40.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                )
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

/** What the pages keep clear for the floating dock: its height and its margins. */
private val APP_DOCK_ROOM = 92.dp

/** The app's three pages as a floating glass dock, the current one in the accent. */
@Composable
private fun AppDock(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tabs = listOf(
        Icons.Filled.FiberManualRecord to R.string.tab_record,
        Icons.Filled.VideoLibrary to R.string.tab_library,
        Icons.Filled.Settings to R.string.tab_settings,
    )
    GlassPill(modifier = modifier.height(68.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, (icon, label) ->
                val on = i == selected
                val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .semantics { this.selected = on }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, null, tint = tint)
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                }
            }
        }
    }
}
