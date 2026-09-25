package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.features.player.seekpreview.LocalSeekPreviewSettings
import com.nuvio.app.features.player.seekpreview.SeekrClient
import com.nuvio.app.features.player.seekpreview.SeekrKeyRepository
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_seek_preview_local
import nuvio.composeapp.generated.resources.settings_seek_preview_local_description
import nuvio.composeapp.generated.resources.settings_seek_preview_local_mobile_data
import nuvio.composeapp.generated.resources.settings_seek_preview_local_mobile_data_description
import nuvio.composeapp.generated.resources.settings_seek_preview_section
import nuvio.composeapp.generated.resources.settings_seekr_api_key
import nuvio.composeapp.generated.resources.settings_seekr_api_key_builtin
import nuvio.composeapp.generated.resources.settings_seekr_api_key_custom
import nuvio.composeapp.generated.resources.settings_seekr_api_key_description
import nuvio.composeapp.generated.resources.settings_seekr_api_key_invalid
import org.jetbrains.compose.resources.stringResource

/** Seek previews (Seekr): lets users use their own API key instead of the shared built-in one. */
@Composable
internal fun SeekPreviewSettingsSection(isTablet: Boolean) {
    if (isIos) return
    val userKey by SeekrKeyRepository.userKey.collectAsStateWithLifecycle()
    val localEnabled by LocalSeekPreviewSettings.enabled.collectAsStateWithLifecycle()
    val localMobileData by LocalSeekPreviewSettings.mobileData.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.settings_seek_preview_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_seek_preview_local),
                description = stringResource(Res.string.settings_seek_preview_local_description),
                checked = localEnabled,
                isTablet = isTablet,
                onCheckedChange = LocalSeekPreviewSettings::setEnabled,
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_seek_preview_local_mobile_data),
                description = stringResource(Res.string.settings_seek_preview_local_mobile_data_description),
                checked = localMobileData,
                enabled = localEnabled,
                isTablet = isTablet,
                onCheckedChange = LocalSeekPreviewSettings::setMobileData,
            )
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_seekr_api_key),
                description = if (userKey.isBlank()) {
                    stringResource(Res.string.settings_seekr_api_key_builtin)
                } else {
                    stringResource(Res.string.settings_seekr_api_key_custom)
                },
                isTablet = isTablet,
                onClick = { showDialog = true },
            )
        }
    }

    if (showDialog) {
        SeekrApiKeyDialog(
            initialValue = userKey,
            onSave = {
                SeekrKeyRepository.setUserKey(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SeekrApiKeyDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initialValue) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val invalidKeyMessage = stringResource(Res.string.settings_seekr_api_key_invalid)

    BasicAlertDialog(onDismissRequest = { if (!isVerifying) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_seekr_api_key),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_seekr_api_key_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSecretTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        errorMessage = null
                    },
                    label = stringResource(Res.string.settings_seekr_api_key),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isVerifying) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val trimmed = value.trim()
                            // Empty falls back to the built-in key; an unchanged key needs no check.
                            if (trimmed.isEmpty() || trimmed == initialValue) {
                                onSave(trimmed)
                                return@TextButton
                            }
                            isVerifying = true
                            scope.launch {
                                val valid = SeekrClient(trimmed).validateKey()
                                isVerifying = false
                                if (valid) onSave(trimmed) else errorMessage = invalidKeyMessage
                            }
                        },
                        enabled = !isVerifying,
                    ) {
                        if (isVerifying) {
                            NuvioLoadingIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(stringResource(Res.string.action_save))
                        }
                    }
                }
            }
        }
    }
}
