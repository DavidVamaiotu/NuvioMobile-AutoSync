package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/**
 * User-supplied subtitle font. [fontName] is null while the built-in font is used.
 * Platforms that cannot import fonts return null from [rememberSubtitleFontPicker].
 */
class SubtitleFontPickerState(
    val fontName: String?,
    val isImporting: Boolean,
    val pick: () -> Unit,
    val reset: () -> Unit,
)

@Composable
expect fun rememberSubtitleFontPicker(): SubtitleFontPickerState?
