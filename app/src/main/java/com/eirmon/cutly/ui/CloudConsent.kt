package com.eirmon.cutly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.eirmon.cutly.R

/**
 * The one place audio is allowed to leave the phone, and it asks every time.
 *
 * A caller parks the action it wants to run in [pending]; the gate shows what will be uploaded
 * and to whom, runs the action on confirm, and calls [onSettled] either way so the caller clears
 * its slot. With no [provider] configured the action runs straight away, because the ViewModel
 * behind it is the one that knows how to say "add a key first".
 */
@Composable
internal fun CloudConsentGate(
    provider: String?,
    pending: (() -> Unit)?,
    onSettled: () -> Unit
) {
    if (pending == null) return
    if (provider == null) {
        LaunchedEffect(pending) {
            pending()
            onSettled()
        }
        return
    }
    ConfirmDialog(
        title = stringResource(R.string.cloud_consent_title, provider),
        body = stringResource(R.string.cloud_consent_body, provider),
        confirmLabel = stringResource(R.string.cloud_consent_upload),
        dismissLabel = stringResource(R.string.cancel),
        destructive = false,
        onConfirm = {
            pending()
            onSettled()
        },
        onDismiss = onSettled
    )
}
