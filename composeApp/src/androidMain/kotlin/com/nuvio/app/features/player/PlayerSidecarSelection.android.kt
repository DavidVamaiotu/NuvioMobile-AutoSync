@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

private const val SIDECAR_SELECTION_TAG = "NuvioPlayer"

internal fun tryAttachExternalSubtitleSidecar(
    player: ExoPlayer,
    sidecar: SidecarSubtitleController,
    url: String,
    headers: Map<String, String>,
    useLibass: Boolean,
    onMimeTypeSelected: (String) -> Unit,
): Boolean {
    if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) return false

    Log.d(SIDECAR_SELECTION_TAG, "setSubtitleUri: using buffer-preserving sidecar for url=$url")
    if (!sidecar.startSidecarAddonSubtitle(url, headers, useLibass)) return false

    onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
    return true
}
