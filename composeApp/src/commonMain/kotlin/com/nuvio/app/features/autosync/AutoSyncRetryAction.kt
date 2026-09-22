package com.nuvio.app.features.autosync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_retry_action
import nuvio.composeapp.generated.resources.autosync_retry_exhausted
import nuvio.composeapp.generated.resources.autosync_retry_failed
import nuvio.composeapp.generated.resources.autosync_retry_trying
import nuvio.composeapp.generated.resources.autosync_retry_updated
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoSyncRetryAction(
    controller: AutoSyncPlayerController,
    modifier: Modifier = Modifier,
) {
    val state by controller.autoSyncRetryState.collectAsState()
    if (!state.available) return

    val buttonText = if (state.busy) {
        stringResource(Res.string.autosync_retry_trying)
    } else {
        stringResource(Res.string.autosync_retry_action)
    }
    val statusText = when (state.status) {
        AutoSyncRetryStatus.UPDATED -> stringResource(Res.string.autosync_retry_updated)
        AutoSyncRetryStatus.EXHAUSTED -> stringResource(Res.string.autosync_retry_exhausted)
        AutoSyncRetryStatus.FAILED -> stringResource(Res.string.autosync_retry_failed)
        AutoSyncRetryStatus.IDLE,
        AutoSyncRetryStatus.TRYING,
        -> null
    }

    Column(modifier = modifier.widthIn(max = 460.dp)) {
        Button(
            onClick = controller::retryWithAnotherReference,
            enabled = !state.busy && !state.exhausted,
        ) {
            Text(buttonText)
        }
        statusText?.let { text ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
