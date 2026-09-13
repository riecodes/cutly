package com.eirmon.cutly.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eirmon.cutly.R
import com.eirmon.cutly.data.Project
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.DialogDivider
import com.eirmon.cutly.ui.theme.DialogSurface
import com.eirmon.cutly.ui.theme.DialogTitle
import com.eirmon.cutly.ui.theme.EditorFaint
import com.eirmon.cutly.ui.theme.EditorMuted
import com.eirmon.cutly.ui.theme.EditorPanel
import com.eirmon.cutly.ui.theme.EditorSurface
import com.eirmon.cutly.ui.theme.Scrim
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The shell: a grid of projects with the camera and the importer one tap away underneath.
 *
 * Long-press a card for rename, duplicate and delete. There is no multi-select; with tens of
 * projects at most, one at a time is the honest shape.
 */
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    thumbFor: (Project) -> File,
    busy: Boolean,
    status: String?,
    error: String?,
    onDismissError: () -> Unit,
    onOpen: (Project) -> Unit,
    onImport: (Uri) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (Project, String) -> Unit,
    onDuplicate: (Project) -> Unit,
    onDelete: (Project) -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> picked?.let(onImport) }
    var menuFor by remember { mutableStateOf<Project?>(null) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorSurface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onOpenSettings)

            error?.let { message -> ErrorRow(message, onDismissError) }

            if (projects.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            thumb = thumbFor(project),
                            enabled = !busy,
                            onClick = { onOpen(project) },
                            onLongClick = { menuFor = project }
                        )
                    }
                }
            }

            BottomTabs(
                enabled = !busy,
                onCamera = onOpenCamera,
                onImport = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
            )
        }

        if (busy) BusyOverlay(status)
    }

    menuFor?.let { project ->
        ChoiceDialog(
            title = project.name,
            choices = listOf(
                stringResource(R.string.project_rename) to { renaming = project; menuFor = null },
                stringResource(R.string.project_duplicate) to { onDuplicate(project); menuFor = null },
                stringResource(R.string.project_delete) to { deleting = project; menuFor = null }
            ),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { menuFor = null }
        )
    }
    renaming?.let { project ->
        RenameDialog(
            initial = project.name,
            onConfirm = { onRename(project, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { project ->
        ConfirmDialog(
            title = stringResource(R.string.project_delete_confirm, project.name),
            body = stringResource(R.string.project_delete_body),
            confirmLabel = stringResource(R.string.project_delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { onDelete(project); deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    val settingsLabel = stringResource(R.string.settings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.projects_title),
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            letterSpacing = (-0.6).sp,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenSettings) {
            Text(
                text = "⚙",
                color = Color.White,
                fontSize = 24.sp,
                modifier = Modifier.semantics { contentDescription = settingsLabel }
            )
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    thumb: File,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val image by produceState<ImageBitmap?>(null, thumb) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(thumb.absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .semantics { role = Role.Button }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(14.dp))
                .background(EditorPanel)
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = formatDuration(project.durationMs),
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Scrim)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = project.name,
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.updatedAt)),
            color = EditorMuted,
            fontFamily = TikTokSans,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.projects_empty),
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.projects_empty_hint),
            color = EditorMuted,
            fontFamily = TikTokSans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BottomTabs(enabled: Boolean, onCamera: () -> Unit, onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0B0D))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab(mark = "▦", label = stringResource(R.string.tab_projects), selected = true, enabled = true) {}
        Tab(mark = "◉", label = stringResource(R.string.tab_camera), selected = false, enabled = enabled, onClick = onCamera)
        Tab(mark = "＋", label = stringResource(R.string.tab_import), selected = false, enabled = enabled, onClick = onImport)
    }
}

@Composable
private fun Tab(mark: String, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        selected -> Color.White
        enabled -> EditorMuted
        else -> EditorFaint
    }
    Column(
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .semantics { role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(mark, color = if (selected) Accent else tint, fontSize = 18.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontFamily = TikTokSans, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(EditorPanel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = Color.White,
            fontFamily = TikTokSans,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.dismiss),
            color = Accent,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .semantics { role = Role.Button }
                .padding(8.dp)
        )
    }
}

@Composable
private fun BusyOverlay(status: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = status ?: stringResource(R.string.working),
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DialogSurface)
        ) {
            Text(
                text = stringResource(R.string.project_rename),
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
            )
            HairLine()
            Row(modifier = Modifier.height(52.dp)) {
                DialogAction(
                    label = stringResource(R.string.cancel),
                    color = DialogDivider.copy(alpha = 1f).let { DialogTitle },
                    bold = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(52.dp)
                )
                DialogAction(
                    label = stringResource(R.string.save),
                    color = Accent,
                    bold = true,
                    onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                    modifier = Modifier.weight(1f).height(52.dp)
                )
            }
        }
    }
}
