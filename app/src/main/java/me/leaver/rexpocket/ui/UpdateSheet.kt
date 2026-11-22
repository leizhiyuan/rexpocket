package me.leaver.rexpocket.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.leaver.rexpocket.R
import me.leaver.rexpocket.update.UpdateFailure
import me.leaver.rexpocket.update.UpdateState

/** What the settings row says: the version offered, how far the download got, or that it is up to date. */
@Composable
internal fun updateDetail(state: UpdateState): String? = when (state) {
    UpdateState.Idle -> null
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_current)
    is UpdateState.Available -> stringResource(R.string.update_available, state.tag)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.tag, state.percent)
    is UpdateState.Ready -> stringResource(R.string.update_ready, state.tag)
    // The cause in a few words, and that a tap tries again; the detail is in Diagnostics.
    is UpdateState.Failed -> stringResource(
        when (state.cause) {
            UpdateFailure.Offline -> R.string.update_failed_offline
            UpdateFailure.RateLimited -> R.string.update_failed_rate_limited
            UpdateFailure.Checksum -> R.string.update_failed_checksum
            UpdateFailure.Other -> R.string.update_failed
        },
    )
}

/**
 * A newer build is published, or is already downloaded. Nothing is installed without the tap here,
 * and the installer closes the app, so both offers say what the next step does.
 */
@Composable
internal fun UpdateOfferDialog(
    tag: String,
    downloaded: Boolean,
    onConfirm: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = Colors.surface,
        title = {
            Text(
                stringResource(if (downloaded) R.string.update_ready_title else R.string.update_available_title, tag),
                color = Colors.ink,
            )
        },
        text = {
            Text(
                stringResource(if (downloaded) R.string.update_ready_body else R.string.update_available_body),
                color = Colors.muted,
                lineHeight = labelLeading,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(if (downloaded) R.string.update_install else R.string.update_download),
                    color = Colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.update_later), color = Colors.muted) }
        },
    )
}
