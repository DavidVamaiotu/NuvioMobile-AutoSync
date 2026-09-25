package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.audio_sync_speech_model_offer_body
import nuvio.composeapp.generated.resources.audio_sync_speech_model_offer_download
import nuvio.composeapp.generated.resources.audio_sync_speech_model_offer_later
import nuvio.composeapp.generated.resources.audio_sync_speech_model_offer_title
import org.jetbrains.compose.resources.stringResource

/**
 * Offered when AutoSync is turned on without the speech model: the audio sync fallback works
 * without it, but recognising the dialogue makes it much faster and more precise.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SpeechModelOfferDialog(
    sizeMb: Int,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = tokens.colors.surfaceDialog,
            shape = tokens.shapes.dialog,
        ) {
            Column(modifier = Modifier.padding(tokens.spacing.dialogPadding)) {
                Text(
                    text = stringResource(Res.string.audio_sync_speech_model_offer_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(tokens.spacing.controlGap))
                Text(
                    text = stringResource(Res.string.audio_sync_speech_model_offer_body, sizeMb),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = tokens.shapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.colors.surfaceCard,
                            contentColor = tokens.colors.textPrimary,
                        ),
                    ) {
                        Text(text = stringResource(Res.string.audio_sync_speech_model_offer_later))
                    }
                    Spacer(modifier = Modifier.width(NuvioTokens.Space.s10))
                    Button(
                        onClick = {
                            onDownload()
                            onDismiss()
                        },
                        shape = tokens.shapes.button,
                    ) {
                        Text(text = stringResource(Res.string.audio_sync_speech_model_offer_download))
                    }
                }
            }
        }
    }
}
