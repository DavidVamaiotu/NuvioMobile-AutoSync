package com.nuvio.app.features.player

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_subtitle_font_invalid
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun rememberSubtitleFontPicker(): SubtitleFontPickerState? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invalidMessage = stringResource(Res.string.settings_playback_subtitle_font_invalid)
    remember(context) { SubtitleFontStore.current(context) }
    val font by SubtitleFontStore.font.collectAsState()
    var isImporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            val ok = SubtitleFontStore.import(context, uri)
            isImporting = false
            if (!ok) Toast.makeText(context, invalidMessage, Toast.LENGTH_LONG).show()
        }
    }
    return SubtitleFontPickerState(
        fontName = font?.familyName,
        isImporting = isImporting,
        pick = {
            runCatching {
                // Font MIME types vary by file manager; the import validates the file instead.
                launcher.launch(arrayOf("*/*"))
            }
        },
        reset = { SubtitleFontStore.clear(context) },
    )
}
