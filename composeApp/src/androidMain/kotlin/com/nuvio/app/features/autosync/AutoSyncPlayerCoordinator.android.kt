@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.audiosync.AudioSyncFallback
import com.nuvio.app.features.player.seekpreview.local.LocalPreviewSources
import androidx.media3.datasource.DataSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_analyze_failed
import nuvio.composeapp.generated.resources.autosync_toast_analyzing
import nuvio.composeapp.generated.resources.autosync_toast_apply_failed
import nuvio.composeapp.generated.resources.autosync_toast_load_failed
import nuvio.composeapp.generated.resources.autosync_toast_match_excellent
import nuvio.composeapp.generated.resources.autosync_toast_match_possible
import nuvio.composeapp.generated.resources.autosync_toast_match_strong
import nuvio.composeapp.generated.resources.autosync_toast_match_weak
import nuvio.composeapp.generated.resources.autosync_toast_no_embedded_subtitles
import nuvio.composeapp.generated.resources.autosync_toast_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_result_drift_corrected
import nuvio.composeapp.generated.resources.autosync_toast_result_in_sync
import nuvio.composeapp.generated.resources.autosync_toast_result_kept
import nuvio.composeapp.generated.resources.autosync_toast_result_localized_ignored
import nuvio.composeapp.generated.resources.autosync_toast_result_replaced
import nuvio.composeapp.generated.resources.autosync_toast_result_within_tolerance
import nuvio.composeapp.generated.resources.autosync_toast_retry_failed
import nuvio.composeapp.generated.resources.autosync_toast_retry_matched
import nuvio.composeapp.generated.resources.autosync_toast_retry_none
import nuvio.composeapp.generated.resources.autosync_toast_retry_weaker
import nuvio.composeapp.generated.resources.autosync_toast_unsupported_renderer
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    sourceAudioUrl: String? = null,
    dataSourceFactory: DataSource.Factory? = null,
) {
    /** Shows an AutoSync toast in the app's language, resolving [message] off the call site. */
    private fun showToast(message: suspend () -> String) {
        scope.launch { Toast.makeText(context, message(), Toast.LENGTH_SHORT).show() }
    }

    private var job: Job? = null
    private var selectedBodyJob: Job? = null
    private var retryJob: Job? = null
    private var retryContext: RetryContext? = null
    private var retryOperationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    /** Syncs to the audio when AutoSync keeps a subtitle's original timing. */
    private val audioFallback = AudioSyncFallback(
        context = context,
        scope = scope,
        player = player,
        sidecar = sidecar,
        sourceUrl = sourceUrl,
        sourceAudioUrl = sourceAudioUrl,
        dataSourceFactory = dataSourceFactory,
        getSubtitleHeaders = getSubtitleHeaders,
        onSubtitleReplaced = { url ->
            onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
            onSubtitleDelayChanged(0)
            appliedListener?.invoke(url, 0)
        },
    )
    /** Lets on-device seek previews collect this stream's keyframes as playback demuxes them. */
    private val localPreviewSource = LocalPreviewSources.register(context, sourceUrl)
    private val _retryState = MutableStateFlow(AutoSyncRetryUiState())
    val retryState: StateFlow<AutoSyncRetryUiState> = _retryState.asStateFlow()

    init {
        // The coordinator is created when the stream opens. Start the embedded index download
        // now so it overlaps player startup instead of beginning when a subtitle is selected.
        AutoSyncPreferencesRepository.ensureLoaded()
        if (AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) {
            EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        }
    }

    fun wrap(controller: PlayerEngineController): PlayerEngineController =
        AutoSyncPlayerEngineController(base = controller, coordinator = this)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
        audioFallback.setCandidates(candidates.map { Triple(it.url, it.language, it.name) })
    }

    fun setContent(type: String, videoId: String) = audioFallback.setContent(type, videoId)

    fun setAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) {
        appliedListener = listener
    }

    private fun invalidateRetryContext() {
        retryOperationToken++
        retryJob?.cancel()
        retryJob = null
        retryContext = null
        _retryState.value = AutoSyncRetryUiState()
    }

    fun cancel() {
        audioFallback.stop()
        job?.cancel()
        job = null
        selectedBodyJob?.cancel()
        selectedBodyJob = null
        invalidateRetryContext()
    }

    /**
     * Attaches [url] through Nuvio's own [attach] without AutoSync. If Nuvio rendered it with the
     * sidecar, records its MIME type; otherwise Nuvio reloads the media item for it, so any
     * previous sidecar subtitle is stopped to keep the two renderers from overlapping.
     */
    fun attachWithoutAutoSync(url: String, attach: (String) -> Unit) {
        cancel()
        val generationBefore = sidecar.currentGenerationFor(url)
        attach(url)
        val generationAfter = sidecar.currentGenerationFor(url)
        if (generationAfter != null && generationAfter != generationBefore) {
            onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        } else {
            sidecar.stopSidecarAddonSubtitle(clearView = true)
        }
    }

    fun onManualSubtitleDelayChanged() {
        if (retryJob?.isActive != true) return
        retryOperationToken++
        retryJob?.cancel()
        retryJob = null
        _retryState.value = _retryState.value.copy(
            busy = false,
            exhausted = false,
            status = AutoSyncRetryStatus.IDLE,
        )
    }

    fun dispose() {
        cancel()
        audioFallback.release()
        LocalPreviewSources.unregister(localPreviewSource)
        appliedListener = null
    }

    fun retryWithAnotherReference() {
        val snapshot = retryContext ?: return
        if (retryJob?.isActive == true || _retryState.value.exhausted) return

        val currentGeneration = sidecar.currentGenerationFor(snapshot.subtitleUrl)
        if (
            sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
            currentGeneration != snapshot.expectedGeneration
        ) {
            invalidateRetryContext()
            return
        }

        val rejectedKeys = mergeRejectedReferenceKeys(
            previous = snapshot.rejectedReferenceKeys,
            referenceKey = snapshot.appliedReference.key,
            equivalentKeys = snapshot.appliedReference.equivalentKeys,
        )
        retryContext = snapshot.copy(rejectedReferenceKeys = rejectedKeys)

        val operationToken = ++retryOperationToken
        _retryState.value = AutoSyncRetryUiState(
            available = true,
            busy = true,
            status = AutoSyncRetryStatus.TRYING,
        )

        retryJob = scope.launch {
            var searchOutcome: AutoSyncReferenceSearchOutcome? = null
            var rejectedAssessment: AutoSyncMatchAssessment? = null
            try {
                val resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = snapshot.subtitleUrl,
                    selectedSubtitleHeaders = snapshot.subtitleHeaders,
                    selectedSubtitleBodyDeferred = CompletableDeferred(snapshot.originalBody),
                    preferredLanguage = getPreferredLanguage(),
                    alternativeSubtitles = emptyList(),
                    alternativeSubtitlesProvider = null,
                    excludedReferenceKeys = rejectedKeys,
                    requiredReferenceSource = snapshot.appliedReference.source,
                    onReferenceSearchOutcome = { outcome -> searchOutcome = outcome },
                    onMatchAssessment = { assessment -> rejectedAssessment = assessment },
                )

                currentCoroutineContext().ensureActive()
                val activeContext = retryContext ?: return@launch
                if (operationToken != retryOperationToken) return@launch
                if (
                    sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
                    sidecar.currentGenerationFor(snapshot.subtitleUrl) != snapshot.expectedGeneration
                ) {
                    invalidateRetryContext()
                    return@launch
                }

                if (resolved == null) {
                    val exhausted = searchOutcome == AutoSyncReferenceSearchOutcome.EXHAUSTED
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        busy = false,
                        exhausted = exhausted,
                        status = if (exhausted) {
                            AutoSyncRetryStatus.EXHAUSTED
                        } else {
                            AutoSyncRetryStatus.FAILED
                        },
                    )
                    val outcome = searchOutcome ?: AutoSyncReferenceSearchOutcome.UNAVAILABLE
                    AutoSyncDebugLog.info {
                        "RETRY operation=$operationToken outcome=$outcome"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY $outcome - current timing kept",
                        )
                    }
                    showToast {
                        rejectedAssessment?.let { assessment ->
                            getString(Res.string.autosync_toast_retry_weaker, assessment.confidencePercent)
                        } ?: getString(Res.string.autosync_toast_retry_none)
                    }
                    return@launch
                }

                if (
                    resolved.subtitleUrl != snapshot.subtitleUrl ||
                    resolved.reference.source != snapshot.appliedReference.source
                ) {
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        status = AutoSyncRetryStatus.FAILED,
                    )
                    AutoSyncDebugLog.warn {
                        "RETRY operation=$operationToken rejected unexpected external/reference source"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY unavailable - current timing kept",
                        )
                    }
                    showToast { getString(Res.string.autosync_toast_retry_none) }
                    return@launch
                }

                val applied = replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = snapshot.subtitleUrl,
                    url = snapshot.subtitleUrl,
                    headers = snapshot.subtitleHeaders,
                    rawBody = snapshot.originalBody,
                    useLibass = getUseLibass(),
                    timeline = resolved.timeline,
                )
                currentCoroutineContext().ensureActive()
                if (operationToken != retryOperationToken) return@launch

                if (!applied) {
                    if (
                        sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
                        sidecar.currentGenerationFor(snapshot.subtitleUrl) != snapshot.expectedGeneration
                    ) {
                        invalidateRetryContext()
                    } else {
                        _retryState.value = AutoSyncRetryUiState(
                            available = true,
                            status = AutoSyncRetryStatus.FAILED,
                        )
                    }
                    AutoSyncDebugLog.warn {
                        "RETRY operation=$operationToken apply=false"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY apply failed - current timing kept",
                        )
                    }
                    showToast { getString(Res.string.autosync_toast_apply_failed) }
                    return@launch
                }

                val committedGeneration =
                    sidecar.currentGenerationFor(snapshot.subtitleUrl)
                        ?: run {
                            invalidateRetryContext()
                            return@launch
                        }
                retryContext = activeContext.copy(
                    appliedReference = resolved.reference,
                    expectedGeneration = committedGeneration,
                )
                onSubtitleDelayChanged(0)
                appliedListener?.invoke(snapshot.subtitleUrl, 0)
                _retryState.value = AutoSyncRetryUiState(
                    available = true,
                    status = AutoSyncRetryStatus.UPDATED,
                )
                AutoSyncDebugLog.info {
                    "RETRY operation=$operationToken applied=true " +
                        "reference=${resolved.reference.key} originalBody=true"
                }
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REFERENCE RETRY applied reference=${resolved.reference.key}",
                    )
                }
                showToast { getString(Res.string.autosync_toast_retry_matched, resolved.assessment.confidencePercent) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (operationToken == retryOperationToken && retryContext != null) {
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        status = AutoSyncRetryStatus.FAILED,
                    )
                }
                AutoSyncDebugLog.error(error) {
                    "RETRY operation=$operationToken failed"
                }
                if (operationToken == retryOperationToken && AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REFERENCE RETRY error - current timing kept",
                    )
                }
                if (operationToken == retryOperationToken) {
                    showToast { getString(Res.string.autosync_toast_retry_failed) }
                }
            } finally {
                if (operationToken == retryOperationToken) {
                    retryJob = null
                    if (_retryState.value.busy) {
                        _retryState.value = _retryState.value.copy(busy = false)
                    }
                }
            }
        }
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        fallbackAttach: (String) -> Unit,
    ) {
        cancel()

        AutoSyncPreferencesRepository.ensureLoaded()
        when (
            decideAutoSyncStart(
                enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
            )
        ) {
            AutoSyncStartAction.RUN -> Unit
            AutoSyncStartAction.ATTACH_ORIGINAL -> {
                fallbackAttach(url)
                return
            }
        }

        showToast { getString(Res.string.autosync_toast_analyzing) }

        val useLibass = getUseLibass()
        val subtitleHeaders = getSubtitleHeaders(url)
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            fallbackAttach(url)
            showToast { getString(Res.string.autosync_toast_unsupported_renderer) }
            return
        }

        // One download feeds both the sidecar renderer and the analysis. It completes with null
        // on failure or cancellation so neither side can wait on it forever.
        val selectedSubtitleBodyDeferred = CompletableDeferred<String?>()
        if (
            !sidecar.startSidecarAddonSubtitle(
                url = url,
                headers = subtitleHeaders,
                useLibass = useLibass,
                rawBodyLoader = {
                    selectedSubtitleBodyDeferred.await()
                        ?: throw IllegalStateException("Subtitle body unavailable")
                },
            )
        ) {
            fallbackAttach(url)
            showToast { getString(Res.string.autosync_toast_load_failed) }
            return
        }

        selectedBodyJob = scope.launch {
            val body = try {
                AutomaticSubtitleSync.downloadSubtitleBody(
                    url = url,
                    headers = subtitleHeaders,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "selected subtitle download failed url=$url: ${error.message}")
                null
            }
            selectedSubtitleBodyDeferred.complete(body)
        }.also { download ->
            download.invokeOnCompletion { selectedSubtitleBodyDeferred.complete(null) }
        }

        fun restoreOriginalSubtitleIfSidecarFailed() {
            if (
                shouldRestoreOriginalSubtitle(
                    activeSidecarSubtitleKey = sidecar.activeSidecarSubtitleKey,
                )
            ) {
                fallbackAttach(url)
            }
        }

        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        audioFallback.arm()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        job = scope.launch {
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            var rejectedAssessment: AutoSyncMatchAssessment? = null
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrl,
                sourceHeaders = sourceHeaders,
                selectedSubtitleUrl = url,
                selectedSubtitleHeaders = subtitleHeaders,
                selectedSubtitleBodyDeferred = selectedSubtitleBodyDeferred,
                preferredLanguage = getPreferredLanguage(),
                alternativeSubtitles = candidateScope.alternativeCandidates(candidates),
                alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) {
                    { candidates }
                } else {
                    null
                },
                onReferenceReady = {},
                onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
                onMatchAssessment = { assessment -> rejectedAssessment = assessment },
            )
            AutoSyncDebugLog.info {
                "candidateScope=${candidateScope.name}"
            }

            if (resolved == null) {
                restoreOriginalSubtitleIfSidecarFailed()
                // Original timing kept: sync it to the audio instead, when AutoSync found nothing
                // to align to or only a weak match (not when the subtitle itself failed to load).
                if (
                    analysisOutcome != AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE &&
                    sidecar.activeSidecarSubtitleKey == url
                ) {
                    audioFallback.takeOver(url)
                } else {
                    audioFallback.disarm()
                }
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REJECT V2 - original sidecar timing kept",
                    )
                }
                showToast {
                    buildAutoSyncFailureToast(
                            analysisOutcome = analysisOutcome,
                            assessment = rejectedAssessment,
                        )
                }
                return@launch
            }

            val chosenUrl = resolved.subtitleUrl
            val timeline = resolved.timeline
            // A confident match whose whole-film correction is within the user's tolerance keeps
            // the selected subtitle's original timing instead of retiming it.
            val toleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value
            val withinToleranceMs = toleranceMs.takeIf {
                it > 0 && chosenUrl == url && timeline.maxAlignmentShiftMs() <= it
            }
            val applied = if (withinToleranceMs != null) {
                sidecar.activeSidecarSubtitleKey == url
            } else if (chosenUrl == url) {
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

            audioFallback.disarm()
            if (!applied) {
                restoreOriginalSubtitleIfSidecarFailed()
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
                showToast { getString(Res.string.autosync_toast_apply_failed) }
                return@launch
            }

            if (chosenUrl != url) {
                onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
            }
            val originalBody = resolved.subtitleBody
            val referenceGeneration = sidecar.currentGenerationFor(chosenUrl)
            val pendingRetryContext =
                if (originalBody != null && referenceGeneration != null) {
                    RetryContext(
                        subtitleUrl = chosenUrl,
                        subtitleHeaders = resolved.subtitleHeaders,
                        originalBody = originalBody,
                        appliedReference = resolved.reference,
                        rejectedReferenceKeys = emptySet(),
                        expectedGeneration = referenceGeneration,
                    )
                } else {
                    null
                }

            onSubtitleDelayChanged(0)
            appliedListener?.invoke(chosenUrl, 0)

            AutoSyncDebugLog.info {
                "AUTO APPLY V2 sidecar=true bufferPreserved=true " +
                    "externalChanged=${chosenUrl != url} groups=${timeline.groups.size} " +
                    "alignment=${timeline.alignmentSource} " +
                    "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                    "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)} finalDelay=0ms " +
                    "maxShift=${"%.1f".format(timeline.maxAlignmentShiftMs())}ms " +
                    "withinTolerance=${withinToleranceMs != null} toleranceMs=$toleranceMs"
            }
            if (AutoSyncDebugLog.ENABLED) {
                AutoSyncDebugLog.finishAndCopy(
                    context = context,
                    decision =
                        if (withinToleranceMs != null) {
                            "WITHIN TOLERANCE ${withinToleranceMs}ms - original timing kept " +
                                "url=$chosenUrl alignment=${timeline.alignmentSource}"
                        } else {
                            "APPLIED V2 sidecar timeline bufferPreserved=true " +
                                "externalChanged=${chosenUrl != url} url=$chosenUrl " +
                                "alignment=${timeline.alignmentSource}"
                        },
                )
            }

            if (pendingRetryContext != null) {
                retryContext = pendingRetryContext
                _retryState.value = AutoSyncRetryUiState(available = true)
            } else {
                invalidateRetryContext()
            }

            showToast {
                buildAutoSyncSuccessToast(
                        replacedSubtitle = chosenUrl != url,
                        scale = timeline.alignmentScale,
                        interceptMs = timeline.alignmentInterceptMs,
                        assessment = resolved.assessment,
                        localizedMismatchIgnored = timeline.localizedMismatchIgnored,
                        withinToleranceMs = withinToleranceMs,
                    )
            }
            Log.i(
                TAG,
                "applied selected=$url chosen=$chosenUrl alignment=${timeline.alignmentSource}",
            )
        }
    }

    private data class RetryContext(
        val subtitleUrl: String,
        val subtitleHeaders: Map<String, String>,
        val originalBody: String,
        val appliedReference: AutoSyncReferenceIdentity,
        val rejectedReferenceKeys: Set<String>,
        val expectedGeneration: Long,
    )
}

private suspend fun AutoSyncMatchAssessment.toastPrefix(): String =
    getString(
        when (strength) {
            AutoSyncMatchStrength.EXCELLENT -> Res.string.autosync_toast_match_excellent
            AutoSyncMatchStrength.STRONG -> Res.string.autosync_toast_match_strong
            AutoSyncMatchStrength.POSSIBLE -> Res.string.autosync_toast_match_possible
            AutoSyncMatchStrength.WEAK -> Res.string.autosync_toast_match_weak
        },
        confidencePercent,
    )

private suspend fun buildAutoSyncSuccessToast(
    replacedSubtitle: Boolean,
    scale: Double,
    interceptMs: Double,
    assessment: AutoSyncMatchAssessment,
    localizedMismatchIgnored: Boolean,
    withinToleranceMs: Int?,
): String {
    val prefix = assessment.toastPrefix()
    val driftCorrected = abs(scale - 1.0) >= 0.0005

    val result = when {
        withinToleranceMs != null ->
            getString(Res.string.autosync_toast_result_within_tolerance, withinToleranceMs)
        localizedMismatchIgnored -> getString(Res.string.autosync_toast_result_localized_ignored)
        replacedSubtitle -> getString(Res.string.autosync_toast_result_replaced)
        driftCorrected -> getString(Res.string.autosync_toast_result_drift_corrected)
        abs(interceptMs) >= 250.0 -> formatAutoSyncOffset(interceptMs)
        else -> getString(Res.string.autosync_toast_result_in_sync)
    }
    return "$prefix • $result"
}

private suspend fun buildAutoSyncFailureToast(
    analysisOutcome: AutoSyncAnalysisOutcome?,
    assessment: AutoSyncMatchAssessment?,
): String =
    when (analysisOutcome) {
        AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE ->
            getString(Res.string.autosync_toast_analyze_failed)
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS ->
            getString(Res.string.autosync_toast_no_embedded_subtitles)
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE ->
            getString(Res.string.autosync_toast_no_reference)
        null -> {
            val resolvedAssessment =
                assessment ?: AutoSyncMatchAssessment(
                    confidencePercent = 0,
                    strength = AutoSyncMatchStrength.WEAK,
                )
            "${resolvedAssessment.toastPrefix()} • ${getString(Res.string.autosync_toast_result_kept)}"
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
