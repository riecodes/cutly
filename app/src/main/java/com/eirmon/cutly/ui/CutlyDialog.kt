package com.eirmon.cutly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * The light confirmation sheet from the reference: white card, centred title, hairline dividers,
 * and a destructive action in the accent red. Material3's AlertDialog cannot be shaped like this
 * without fighting it, so this is built from a bare Dialog.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    dismissLabel: String,
    body: String? = null,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DialogSurface)
        ) {
            DialogTitleText(title)
            if (body != null) {
                Text(
                    text = body,
                    color = DialogNeutral,
                    fontFamily = TikTokSans,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                )
            }
            HairLine()
            // Each action must fill the row's height, otherwise the Box wraps to the text and the
            // labels ride the top edge instead of centring in the button strip.
            Row(modifier = Modifier.height(52.dp)) {
                DialogAction(
                    label = dismissLabel,
                    color = DialogNeutral,
                    bold = false,
                    onClick = onDismiss,
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
                    label = confirmLabel,
                    color = if (destructive) Accent else DialogTitle,
                    bold = true,
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/** Same sheet, stacked, for when there are more than two outcomes. */
@Composable
internal fun ChoiceDialog(
    title: String,
    choices: List<Pair<String, () -> Unit>>,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DialogSurface)
        ) {
            DialogTitleText(title)
            choices.forEach { (label, action) ->
                HairLine()
                DialogAction(
                    label = label,
                    color = DialogTitle,
                    bold = true,
                    onClick = action,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }
            HairLine()
            DialogAction(
                label = dismissLabel,
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
private fun DialogTitleText(title: String) {
    Text(
        text = title,
        color = DialogTitle,
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    )
}

@Composable
internal fun HairLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DialogDivider)
    )
}

@Composable
internal fun DialogAction(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    bold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = TikTokSans,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 16.sp
        )
    }
}
