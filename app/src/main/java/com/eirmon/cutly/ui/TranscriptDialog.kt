package com.eirmon.cutly.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.DialogDivider
import com.eirmon.cutly.ui.theme.DialogNeutral
import com.eirmon.cutly.ui.theme.DialogSurface
import com.eirmon.cutly.ui.theme.DialogTitle
import com.eirmon.cutly.ui.theme.TikTokSans

/**
 * The take's transcript, in the same white card as the other dialogs but editable.
 *
 * Editable rather than read-only on purpose: Tagalog word error rates run well above English on
 * every speech model, and proper nouns come back wrong often enough that a transcript you cannot
 * fix is a transcript you retype somewhere else.
 */
@Composable
internal fun TranscriptDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DialogSurface)
        ) {
            Text(
                text = "Transcript",
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)
            )

            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    color = DialogTitle,
                    fontFamily = TikTokSans,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    // Capped rather than free: a ten-minute take would otherwise push the buttons
                    // off the bottom of the screen.
                    .heightIn(min = 120.dp, max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                HairLine()
            }

            Row(modifier = Modifier.height(52.dp)) {
                DialogAction(
                    label = "Copy",
                    color = DialogTitle,
                    bold = true,
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        // Android 13+ shows its own copy confirmation, so there is none here.
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cutly transcript", text))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(DialogDivider)
                )
                DialogAction(
                    label = "Done",
                    color = DialogNeutral,
                    bold = false,
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}
