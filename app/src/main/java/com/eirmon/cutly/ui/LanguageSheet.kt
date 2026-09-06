package com.eirmon.cutly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eirmon.cutly.transcribe.TranscriptionLanguage
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.DialogDivider
import com.eirmon.cutly.ui.theme.DialogNeutral
import com.eirmon.cutly.ui.theme.DialogSurface
import com.eirmon.cutly.ui.theme.DialogTitle
import com.eirmon.cutly.ui.theme.Faint
import com.eirmon.cutly.ui.theme.Hairline
import com.eirmon.cutly.ui.theme.Muted
import com.eirmon.cutly.ui.theme.TikTokSans

/**
 * Picks the transcription language from a searchable list.
 *
 * A row of chips was the wrong shape for this. The list is whatever the phone's recogniser
 * reports — thirty-odd entries on a Galaxy A56 — so a horizontal strip meant swiping blindly past
 * options with no way to jump to one. A dialog with a filter turns "find Vietnamese" into typing
 * three letters, and gives every row a full-width target instead of a chip the width of its label.
 */
@Composable
internal fun LanguageSheet(
    languages: List<TranscriptionLanguage>,
    selected: TranscriptionLanguage?,
    onSelect: (TranscriptionLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    // Matching the tag as well as the label means "fil", "tl" and "PH" all find what you expect,
    // and the tag is what the recogniser actually keys on.
    val shown = remember(languages, query) {
        val needle = query.trim()
        if (needle.isEmpty()) languages
        else languages.filter {
            it.label.contains(needle, ignoreCase = true) ||
                it.tag.contains(needle, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DialogSurface)
        ) {
            Text(
                text = "Transcription language",
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )

            SearchField(query = query, onQueryChange = { query = it })

            HairLine()

            if (shown.isEmpty()) {
                Text(
                    text = "Nothing matches that.",
                    color = Muted,
                    fontFamily = TikTokSans,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.tag }) { language ->
                        LanguageRow(
                            language = language,
                            selected = language.tag == selected?.tag,
                            onClick = { onSelect(language) }
                        )
                    }
                }
            }

            HairLine()

            DialogAction(
                label = "Close",
                color = DialogNeutral,
                bold = false,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Hairline)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (query.isEmpty()) {
            Text(
                text = "Search languages",
                color = Faint,
                fontFamily = TikTokSans,
                fontSize = 14.sp
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LanguageRow(
    language: TranscriptionLanguage,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            // A comfortable target for a finger, rather than a chip sized to its own text.
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = language.label,
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 19.sp
            )
            Text(
                // Said plainly rather than with an arrow glyph: choosing one of these starts a
                // download and returns no transcript on the first try, which is worth a sentence.
                text = if (language.installed) language.tag
                else "${language.tag} · downloads on first use",
                color = if (language.installed) Faint else Muted,
                fontFamily = TikTokSans,
                fontSize = 11.sp
            )
        }

        if (selected) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = "✓",
                color = Accent,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DialogDivider)
    )
}
