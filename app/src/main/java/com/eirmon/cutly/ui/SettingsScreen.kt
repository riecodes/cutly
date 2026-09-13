package com.eirmon.cutly.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eirmon.cutly.BuildConfig
import com.eirmon.cutly.SettingsViewModel
import com.eirmon.cutly.CutlyApp
import com.eirmon.cutly.R
import com.eirmon.cutly.data.AppSettings
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.transcribe.SherpaModelState
import com.eirmon.cutly.transcribe.TranscriptionEngine
import com.eirmon.cutly.ui.theme.SheetMuted
import com.eirmon.cutly.ui.theme.SheetSurface
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keys, links and the version. No ViewModel: the two fields write straight to [AppSettings] on
 * every keystroke, which is the whole persistence story for two strings.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openAiKey by rememberSaveable { mutableStateOf(settings.openAiKey) }
    var geminiKey by rememberSaveable { mutableStateOf(settings.geminiKey) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var languageSheetOpen by rememberSaveable { mutableStateOf(false) }
    val crashLog by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { CutlyApp.lastCrash(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(title = stringResource(R.string.settings), onBack = onBack)

        SectionLabel(stringResource(R.string.settings_transcription))
        Text(
            text = stringResource(R.string.settings_transcription_help),
            color = SheetMuted,
            fontFamily = TikTokSans,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        EngineRow(
            engine = state.engine,
            modelReady = state.sherpaModel is SherpaModelState.Ready,
            cloudReady = state.cloudProvider != null,
            onSelect = viewModel::setEngine
        )
        when (state.engine) {
            TranscriptionEngine.SYSTEM -> if (state.languages.isEmpty()) {
                Meta(stringResource(R.string.settings_language_none))
            } else {
                LinkRow(
                    "${stringResource(R.string.settings_language)}: ${state.language?.label ?: "…"}"
                ) { languageSheetOpen = true }
            }
            TranscriptionEngine.SHERPA, TranscriptionEngine.CLOUD -> Unit
        }
        WhisperRow(
            model = state.sherpaModel,
            onDownload = viewModel::downloadSherpaModel,
            onDelete = viewModel::deleteSherpaModel
        )

        SectionLabel(stringResource(R.string.settings_cloud))
        Text(
            text = stringResource(R.string.settings_cloud_help),
            color = SheetMuted,
            fontFamily = TikTokSans,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        KeyField(
            label = stringResource(R.string.settings_openai_key),
            value = openAiKey,
            reveal = reveal,
            onValueChange = {
                openAiKey = it
                settings.openAiKey = it
                viewModel.refreshCloud()
            }
        )
        Spacer(Modifier.height(10.dp))
        KeyField(
            label = stringResource(R.string.settings_gemini_key),
            value = geminiKey,
            reveal = reveal,
            onValueChange = {
                geminiKey = it
                settings.geminiKey = it
                viewModel.refreshCloud()
            }
        )
        TextButton(onClick = { reveal = !reveal }) {
            Text(
                text = stringResource(if (reveal) R.string.settings_hide_keys else R.string.settings_show_keys),
                color = Accent,
                fontFamily = TikTokSans
            )
        }

        SectionLabel(stringResource(R.string.settings_about))
        LinkRow(stringResource(R.string.settings_privacy)) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppSettings.PRIVACY_URL)))
        }
        LinkRow(stringResource(R.string.settings_source)) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppSettings.SOURCE_URL)))
        }
        LinkRow(stringResource(R.string.settings_licenses), onClick = onOpenLicenses)
        crashLog?.let { log ->
            LinkRow(stringResource(R.string.settings_copy_crash)) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Cutly crash log", log))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            color = SheetMuted,
            fontFamily = TikTokSans,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(28.dp))
    }

    if (languageSheetOpen) {
        LanguageSheet(
            languages = state.languages,
            selected = state.language,
            onSelect = {
                viewModel.setLanguage(it)
                languageSheetOpen = false
            },
            onDismiss = { languageSheetOpen = false }
        )
    }
}

@Composable
private fun EngineRow(
    engine: TranscriptionEngine,
    modelReady: Boolean,
    cloudReady: Boolean,
    onSelect: (TranscriptionEngine) -> Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(SheetSurface)
    ) {
        EngineChoice(stringResource(R.string.engine_phone), engine == TranscriptionEngine.SYSTEM, true, Modifier.weight(1f)) {
            onSelect(TranscriptionEngine.SYSTEM)
        }
        EngineChoice(stringResource(R.string.engine_whisper), engine == TranscriptionEngine.SHERPA, modelReady, Modifier.weight(1f)) {
            onSelect(TranscriptionEngine.SHERPA)
        }
        EngineChoice(stringResource(R.string.engine_cloud), engine == TranscriptionEngine.CLOUD, cloudReady, Modifier.weight(1f)) {
            onSelect(TranscriptionEngine.CLOUD)
        }
    }
}

@Composable
private fun EngineChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = when {
            selected -> Color.White
            enabled -> Color.White
            else -> SheetMuted
        },
        fontFamily = TikTokSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) Accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.RadioButton }
            .padding(vertical = 14.dp)
    )
}

@Composable
private fun WhisperRow(model: SherpaModelState, onDownload: () -> Unit, onDelete: () -> Unit) {
    when (model) {
        SherpaModelState.Missing -> LinkRow(stringResource(R.string.whisper_get), onClick = onDownload)
        is SherpaModelState.Downloading -> {
            val percent = if (model.totalBytes <= 0) 0
            else (model.downloadedBytes * 100 / model.totalBytes).coerceIn(0, 100).toInt()
            Meta(stringResource(R.string.whisper_downloading, percent))
            LinkRow(stringResource(R.string.whisper_cancel), onClick = onDelete)
        }
        SherpaModelState.Verifying -> Meta(stringResource(R.string.whisper_verifying))
        SherpaModelState.Ready -> {
            Meta(stringResource(R.string.whisper_ready))
            LinkRow(stringResource(R.string.whisper_delete), onClick = onDelete)
        }
        is SherpaModelState.Failed -> {
            Meta(stringResource(R.string.whisper_failed, model.message))
            LinkRow(stringResource(R.string.whisper_retry), onClick = onDownload)
        }
    }
}

@Composable
private fun Meta(text: String) {
    Text(
        text = text,
        color = SheetMuted,
        fontFamily = TikTokSans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

/** The licence texts bundled as an asset at build time, straight from THIRD_PARTY_LICENSES.txt. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("THIRD_PARTY_LICENSES.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(title = stringResource(R.string.settings_licenses), onBack = onBack)
        SelectionContainer {
            Text(
                text = text,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    val back = stringResource(R.string.back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Text(
                text = "‹",
                color = Color.White,
                fontSize = 30.sp,
                modifier = Modifier.semantics { contentDescription = back }
            )
        }
        Text(
            text = title,
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text.uppercase(),
        color = Accent,
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    reveal: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White, fontFamily = TikTokSans, fontSize = 15.sp)
        Text(text = "›", color = SheetMuted, fontSize = 18.sp)
    }
}
