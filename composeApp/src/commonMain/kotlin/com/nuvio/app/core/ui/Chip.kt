package com.nuvio.app.core.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Chip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceCard,
            labelColor = colors.textSecondary,
            iconColor = colors.textSecondary,
            selectedContainerColor = colors.accent,
            selectedLabelColor = colors.onAccent,
            selectedLeadingIconColor = colors.onAccent,
            disabledContainerColor = colors.surfaceCard,
            disabledLabelColor = colors.textDisabled,
            disabledLeadingIconColor = colors.textDisabled,
            disabledSelectedContainerColor = colors.focusBackground,
        ),
    )
}
