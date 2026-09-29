package com.eirmon.cutly.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eirmon.cutly.R
import com.eirmon.cutly.data.TranscriptEntry
import com.eirmon.cutly.transcribe.Segment
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.EditorFaint
import com.eirmon.cutly.ui.theme.EditorLine
import com.eirmon.cutly.ui.theme.EditorMuted
import com.eirmon.cutly.ui.theme.EditorPanel
import com.eirmon.cutly.ui.theme.EditorSurface
import com.eirmon.cutly.ui.theme.TikTokSans
import java.text.DateFormat
import java.util.Date

/**
 * Picks a video and hands it to [onTranscribe], through the cloud consent gate when [uploads].
 * Returns the action a button calls, and emits the consent dialog where it is called.
 */
@Composable
internal fun rememberTranscribeAction(
    uploads: Boolean,
    cloudProvider: String?,
    onTranscribe: (Uri) -> Unit
): () -> Unit {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> picked?.let(onTranscribe) }
    var pendingCloud by remember { mutableStateOf<(() -> Unit)?>(null) }
    CloudConsentGate(provider = cloudProvider, pending = pendingCloud, onSettled = { pendingCloud = null })

    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    return { if (uploads) pendingCloud = pick else pick() }
}

/** The quick tool at the top of the project grid. */
@Composable
internal fun TranscribeTool(enabled: Boolean, needsKey: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EditorPanel)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("T", color = if (enabled) Accent else EditorFaint, fontFamily = TikTokSans, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.transcribe),
                color = if (enabled) Color.White else EditorFaint,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                stringResource(if (needsKey) R.string.transcribe_needs_key else R.string.transcribe_subtitle),
                color = EditorMuted,
                fontFamily = TikTokSans,
                fontSize = 11.sp
            )
        }
        Text("›", color = EditorMuted, fontSize = 22.sp)
    }
}

/** The three newest transcripts, and the one being made, as a small table under the tool. */
@Composable
internal fun RecentTranscripts(
    recent: List<TranscriptEntry>,
    running: String?,
    status: String?,
    error: String?,
    onCancel: () -> Unit,
    onOpen: (TranscriptEntry) -> Unit,
    onSeeAll: () -> Unit,
    onDismissError: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.transcripts_heading),
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            if (recent.isNotEmpty()) TextAction(stringResource(R.string.transcripts_see_all), onSeeAll)
        }
        error?.let { ErrorRow(it, onDismissError) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(EditorPanel)
        ) {
            running?.let { TranscribingRow(it, status, onCancel) }
            recent.forEachIndexed { index, entry ->
                if (index > 0 || running != null) HorizontalDivider(color = EditorLine)
                TranscriptRow(entry, onClick = { onOpen(entry) })
            }
        }
    }
}

/** One history line: name, then when, how long, which engine, and how many lines came back. */
@Composable
internal fun TranscriptRow(entry: TranscriptEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(meta(entry), color = EditorMuted, fontFamily = TikTokSans, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (entry.segments.isEmpty()) stringResource(R.string.transcript_no_speech)
            else pluralStringResource(R.plurals.transcript_lines, entry.segments.size, entry.segments.size),
            color = EditorFaint,
            fontFamily = TikTokSans,
            fontSize = 11.sp
        )
    }
}

@Composable
internal fun TranscribingRow(name: String, status: String?, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            status?.let {
                Text(it, color = EditorMuted, fontFamily = TikTokSans, fontSize = 11.sp, maxLines = 1)
            }
        }
        TextAction(stringResource(R.string.cancel).uppercase(), onCancel)
    }
}

/** Every quick transcript, newest first, with the tool again at the top. */
@Composable
fun TranscriptsScreen(
    entries: List<TranscriptEntry>,
    running: String?,
    status: String?,
    error: String?,
    engineLabel: String,
    needsKey: Boolean,
    uploads: Boolean,
    cloudProvider: String?,
    onBack: () -> Unit,
    onTranscribe: (Uri) -> Unit,
    onCancel: () -> Unit,
    onOpen: (TranscriptEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit
) {
    val transcribe = rememberTranscribeAction(uploads, cloudProvider, onTranscribe)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorSurface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        ScreenHeader(stringResource(R.string.transcripts_title), onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                TranscribeTool(
                    enabled = running == null,
                    needsKey = needsKey,
                    onClick = if (needsKey) onOpenSettings else transcribe
                )
                Text(
                    stringResource(R.string.transcribe_detail, engineLabel),
                    color = EditorMuted,
                    fontFamily = TikTokSans,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                error?.let { ErrorRow(it, onDismissError) }
                running?.let {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(EditorPanel)) {
                        TranscribingRow(it, status, onCancel)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (entries.isEmpty() && running == null) {
                    Text(
                        stringResource(R.string.transcripts_empty),
                        color = EditorMuted,
                        fontFamily = TikTokSans,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp)
                    )
                }
            }
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                if (index > 0) HorizontalDivider(color = EditorLine)
                TranscriptRow(entry, onClick = { onOpen(entry) })
            }
        }
    }
}

/** One transcript, selectable, with copy, share and delete. */
@Composable
fun TranscriptScreen(entry: TranscriptEntry?, onBack: () -> Unit, onDelete: (TranscriptEntry) -> Unit) {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorSurface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Null while the history loads after process death, or for a moment after a delete.
        ScreenHeader(entry?.name.orEmpty(), onBack)
        if (entry == null) return@Column
        val text = remember(entry) { Segment.render(entry.segments) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(meta(entry), color = EditorMuted, fontFamily = TikTokSans, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextAction(stringResource(R.string.transcript_copy)) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Cutly transcript", text))
            }
            TextAction(stringResource(R.string.transcript_share)) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, entry.name)
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, entry.name))
            }
            TextAction(stringResource(R.string.transcript_delete)) { confirming = true }
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(text, color = Color.White, fontFamily = TikTokSans, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }

    if (confirming && entry != null) {
        ConfirmDialog(
            title = stringResource(R.string.transcript_delete_confirm, entry.name),
            body = stringResource(R.string.transcript_delete_body),
            confirmLabel = stringResource(R.string.project_delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { confirming = false; onDelete(entry) },
            onDismiss = { confirming = false }
        )
    }
}

private fun meta(entry: TranscriptEntry): String = listOfNotNull(
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.createdAt)),
    entry.durationMs.takeIf { it > 0 }?.let(::formatDuration),
    entry.engine.ifBlank { null }
).joinToString(" · ")

/** The small bold caps text buttons used in headers here and in the editor. */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Accent,
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(8.dp)
    )
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    val back = stringResource(R.string.back)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.semantics { contentDescription = back })
        }
        Text(
            title,
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
