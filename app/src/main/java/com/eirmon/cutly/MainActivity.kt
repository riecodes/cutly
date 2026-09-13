package com.eirmon.cutly

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.eirmon.cutly.ui.CameraScreen
import com.eirmon.cutly.ui.EditorScreen
import com.eirmon.cutly.ui.LicensesScreen
import com.eirmon.cutly.ui.ProjectOpening
import com.eirmon.cutly.ui.ProjectsScreen
import com.eirmon.cutly.ui.SettingsScreen
import com.eirmon.cutly.ui.theme.CutlyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Five destinations and one string argument. A list in saved state is the whole back stack; a
 * navigation library would add a dependency and a route DSL for the same five `when` branches.
 */
private sealed interface Screen {
    data object Projects : Screen
    data object Camera : Screen
    /** [projectId] is null while an import or a merged take is still becoming a project. */
    data class Editor(val projectId: String?) : Screen
    data object Settings : Screen
    data object Licenses : Screen

    fun encode(): String = when (this) {
        Projects -> "projects"
        Camera -> "camera"
        is Editor -> "editor:${projectId.orEmpty()}"
        Settings -> "settings"
        Licenses -> "licenses"
    }

    companion object {
        fun decode(value: String): Screen = when {
            value == "camera" -> Camera
            value == "settings" -> Settings
            value == "licenses" -> Licenses
            value.startsWith("editor:") -> Editor(value.removePrefix("editor:").ifBlank { null })
            else -> Projects
        }
    }
}

private val ScreenStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> mutableStateListOf<Screen>().apply { addAll(saved.map(Screen::decode)) } }
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A take can run a full minute with no touch input; without this the screen dims mid-clip.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pruneStaleTemps()
        setContent {
            CutlyTheme {
                val view = LocalView.current
                // Every surface is dark now, so the bars are set once.
                LaunchedEffect(Unit) {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                App()
            }
        }
    }

    @OptIn(UnstableApi::class)
    @Composable
    private fun App(editor: EditorViewModel = viewModel()) {
        val stack = rememberSaveable(saver = ScreenStackSaver) {
            mutableStateListOf<Screen>(Screen.Projects)
        }
        val state by editor.state.collectAsStateWithLifecycle()

        fun push(screen: Screen) {
            stack.add(screen)
        }
        fun pop() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex)
        }

        BackHandler(enabled = stack.size > 1) { pop() }

        when (val top = stack.last()) {
            Screen.Projects -> {
                // Runs every time the grid comes back, which covers returning from Settings.
                LaunchedEffect(Unit) {
                    editor.refreshSettings()
                    editor.refreshProjects()
                }
                ProjectsScreen(
                    projects = state.projects,
                    thumbFor = { editor.store.thumb(it.id) },
                    busy = state.isBusy,
                    status = state.status,
                    error = state.error,
                    onDismissError = editor::dismissError,
                    onOpen = { project ->
                        editor.open(project.id)
                        push(Screen.Editor(project.id))
                    },
                    onImport = { uri ->
                        editor.import(uri)
                        push(Screen.Editor(null))
                    },
                    onOpenCamera = { push(Screen.Camera) },
                    onOpenSettings = { push(Screen.Settings) },
                    onRename = { project, name -> editor.renameProject(project.id, name) },
                    onDuplicate = { editor.duplicateProject(it.id) },
                    onDelete = { editor.deleteProject(it.id) }
                )
            }
            Screen.Camera -> CameraScreen(
                onOpenInEditor = { merged ->
                    editor.adoptTake(merged)
                    pop()
                    push(Screen.Editor(null))
                }
            )
            is Screen.Editor -> {
                val review = state.review
                // After process death the ViewModel is empty; the id on the stack reopens it.
                // An import that failed has no id and no review, so it falls back to the grid.
                LaunchedEffect(top.projectId, review?.projectId, state.isBusy, state.error) {
                    if (review == null && !state.isBusy) {
                        if (top.projectId != null && state.error == null) editor.open(top.projectId)
                        else pop()
                    }
                }
                if (review != null) {
                    // Slider drafts commit only on release, so this rebuilds once per edit.
                    val preview = remember(review.keep, review.captions, review.captionsEnabled) {
                        editor.previewComposition()
                    }
                    EditorScreen(
                        review = review,
                        status = state.status,
                        error = state.error,
                        preview = preview,
                        engineLabel = state.engineLabel,
                        needsKey = state.needsKey,
                        uploads = state.uploads,
                        cloudProvider = state.cloudProvider,
                        onOpenSettings = { push(Screen.Settings) },
                        onSettingsChange = editor::updateSettings,
                        onRedetect = editor::redetect,
                        onClipChange = editor::updateClip,
                        onRemoveClip = editor::removeClip,
                        onAddCaptions = editor::addCaptions,
                        onCaptionsEnabled = editor::setCaptionsEnabled,
                        onTranscribe = editor::transcribe,
                        onCancelTranscription = editor::cancelTranscription,
                        onSave = editor::save,
                        onDismiss = {
                            editor.closeReview()
                            pop()
                        }
                    )
                } else {
                    ProjectOpening(state.status)
                }
            }
            Screen.Settings -> {
                // Choices made in Settings apply to the next transcript without a restart.
                LaunchedEffect(Unit) { editor.refreshSettings() }
                SettingsScreen(
                    onBack = ::pop,
                    onOpenLicenses = { push(Screen.Licenses) }
                )
            }
            Screen.Licenses -> LicensesScreen(onBack = ::pop)
        }
    }

    /**
     * Export and transcription temps live in the cache root and are deleted by the code that made
     * them, which a crash or a process kill skips. Anything of ours older than an hour is not in
     * use by any job that could still be running.
     */
    private fun pruneStaleTemps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("cutly_") && file.lastModified() < cutoff
            }?.forEach { it.delete() }
        }
    }

    private companion object {
        const val STALE_TEMP_MS = 60L * 60 * 1000
    }
}
