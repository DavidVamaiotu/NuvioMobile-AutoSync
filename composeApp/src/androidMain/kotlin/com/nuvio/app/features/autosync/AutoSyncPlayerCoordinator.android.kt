@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.AutoSyncSubtitleCandidate
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.SubtitleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "NuvioAutoSyncPlayer"

internal class AutoSyncPlayerCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getUseLibass: () -> Boolean,
    private val getPreferredLanguage: () -> String?,
    private val onMimeTypeSelected: (String) -> Unit,
    private val onSubtitleDelayChanged: (Int) -> Unit,
) {
    private var job: Job? = null
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) {
        appliedListener = listener
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun dispose() {
        cancel()
        appliedListener = null
    }

    fun start(
        url: String,
        attachSubtitleOnReject: Boolean,
        fallbackAttach: (String) -> Unit,
    ) {
        cancel()

        Toast.makeText(
            context,
            "Auto Sync V2 started",
            Toast.LENGTH_SHORT,
        ).show()

        val useLibass = getUseLibass()
        val subtitleHeaders = getSubtitleHeaders(url)
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            if (attachSubtitleOnReject) fallbackAttach(url)
            Toast.makeText(
                context,
                "Auto Sync V2 failed: unsupported subtitle renderer",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (
            !sidecar.startSidecarAddonSubtitle(
                url = url,
                headers = subtitleHeaders,
                useLibass = useLibass,
                rawBodyLoader = {
                    AutomaticSubtitleSync.downloadSubtitleBody(
                        url = url,
                        headers = subtitleHeaders,
                    )
                },
            )
        ) {
            if (attachSubtitleOnReject) fallbackAttach(url)
            Toast.makeText(
                context,
                "Auto Sync V2 failed: subtitle could not be loaded",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val selectedSubtitleBodyDeferred = sidecar.rawBodyDeferredFor(url)

        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        job = scope.launch {
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrl,
                sourceHeaders = sourceHeaders,
                selectedSubtitleUrl = url,
                selectedSubtitleHeaders = subtitleHeaders,
                selectedSubtitleBodyDeferred = selectedSubtitleBodyDeferred,
                preferredLanguage = getPreferredLanguage(),
                alternativeSubtitles = candidates,
                alternativeSubtitlesProvider = { candidates },
                alternativeSubtitlesLoadingProvider = {
                    SubtitleRepository.isLoading.value
                },
                onReferenceReady = {},
            )

            if (resolved == null) {
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REJECT V2 - original sidecar timing kept",
                    )
                }
                Toast.makeText(
                    context,
                    "Auto Sync V2 failed: no reliable match",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val chosenUrl = resolved.subtitleUrl
            val timeline = resolved.timeline
            val applied = if (chosenUrl == url) {
                applyAutoSyncSidecarTimeline(
                    sidecar = sidecar,
                    url = url,
                    timeline = timeline,
                )
            } else if (
                sidecar.activeSidecarSubtitleKey == null &&
                sidecar.startSidecarAddonSubtitle(
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
                    useLibass = useLibass,
                    rawBodyLoader = resolved.subtitleBody?.let { body ->
                        suspend { body }
                    },
                )
            ) {
                AutoSyncDebugLog.info {
                    "replacement sidecar attached after selected subtitle load failure"
                }
                applyAutoSyncSidecarTimeline(
                    sidecar = sidecar,
                    url = chosenUrl,
                    timeline = timeline,
                )
            } else {
                replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = url,
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
                    rawBody = resolved.subtitleBody,
                    useLibass = useLibass,
                    timeline = timeline,
                )
            }

            if (!applied) {
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision =
                            if (chosenUrl == url) {
                                "REJECT V2 - sidecar changed or was unavailable before apply"
                            } else {
                                "REJECT V2 replacement - original sidecar preserved"
                            },
                    )
                }
                Toast.makeText(
                    context,
                    "Auto Sync V2 failed: could not apply sync",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            if (chosenUrl != url) {
                onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
            }
            onSubtitleDelayChanged(0)
            appliedListener?.invoke(chosenUrl, 0)

            AutoSyncDebugLog.info {
                "AUTO APPLY V2 sidecar=true bufferPreserved=true " +
                    "externalChanged=${chosenUrl != url} groups=${timeline.groups.size} " +
                    "alignment=${timeline.alignmentSource} " +
                    "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                    "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)} finalDelay=0ms"
            }
            if (AutoSyncDebugLog.ENABLED) {
                AutoSyncDebugLog.finishAndCopy(
                    context = context,
                    decision =
                        "APPLIED V2 sidecar timeline bufferPreserved=true " +
                            "externalChanged=${chosenUrl != url} url=$chosenUrl " +
                            "alignment=${timeline.alignmentSource}",
                )
            }
            Toast.makeText(
                context,
                buildAutoSyncSuccessToast(
                    replacedSubtitle = chosenUrl != url,
                    scale = timeline.alignmentScale,
                    interceptMs = timeline.alignmentInterceptMs,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            Log.i(
                TAG,
                "applied selected=$url chosen=$chosenUrl alignment=${timeline.alignmentSource}",
            )
        }
    }
}

private fun buildAutoSyncSuccessToast(
    replacedSubtitle: Boolean,
    scale: Double,
    interceptMs: Double,
): String {
    val driftCorrected = abs(scale - 1.0) >= 0.0005
    val prefix = if (replacedSubtitle) {
        "Auto Sync V2: subtitle replaced"
    } else {
        "Auto Sync V2 succeeded"
    }

    return when {
        driftCorrected -> "$prefix • drift corrected"
        abs(interceptMs) >= 50.0 -> "$prefix • ${formatAutoSyncOffset(interceptMs)}"
        else -> "$prefix • already in sync"
    }
}

private fun formatAutoSyncOffset(offsetMs: Double): String {
    val roundedMs = offsetMs.roundToInt()
    if (abs(roundedMs) < 1_000) {
        return "${if (roundedMs > 0) "+" else ""}$roundedMs ms"
    }

    val tenths = (roundedMs / 100.0).roundToInt()
    val whole = tenths / 10
    val decimal = abs(tenths % 10)
    return "${if (tenths > 0) "+" else ""}$whole.$decimal s"
}
