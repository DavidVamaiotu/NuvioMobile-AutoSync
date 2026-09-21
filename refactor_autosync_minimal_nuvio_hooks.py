#!/usr/bin/env python3
from pathlib import Path
import subprocess

ROOT = Path.cwd()
EXPECTED_HEAD = "5e7957f4d90503b5b803f951d4a13d240fe8b992"

if not (ROOT / "composeApp").exists():
    raise SystemExit("Run this from the root of NuvioMobile-AutoSync.")

branch = subprocess.check_output(
    ["git", "rev-parse", "--abbrev-ref", "HEAD"], text=True
).strip()
if branch != "autosync-v2-timeline":
    raise SystemExit(f"Wrong branch: {branch}. Switch to autosync-v2-timeline first.")

head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
if head != EXPECTED_HEAD:
    raise SystemExit(
        "This refactor targets the clean AutoSync head "
        f"{EXPECTED_HEAD[:10]}. Current HEAD is {head[:10]}. "
        "Do not stack it on the earlier transactional-fix patch."
    )

if subprocess.run(["git", "diff", "--quiet"]).returncode != 0:
    raise SystemExit("Tracked working-tree changes exist. Commit/stash them first.")
if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode != 0:
    raise SystemExit("Staged changes exist. Commit/stash them first.")

def read(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"Missing expected file: {rel}")
    return p.read_text()

def write(rel, text):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)

def require(text, needle, label):
    if needle not in text:
        raise SystemExit(f"Could not find {label}: {needle[:100]!r}")

def replace_once(text, old, new, label):
    require(text, old, label)
    return text.replace(old, new, 1)

engine_common_path = "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerEngine.kt"
engine_android_path = "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt"
sidecar_path = "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSidecarSubtitles.kt"
runtime_ui_path = "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt"
track_actions_path = "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeTrackActions.kt"
settings_path = "composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackSettingsPage.kt"
strings_path = "composeApp/src/commonMain/composeResources/values/strings.xml"

engine_common = read(engine_common_path)
engine_android = read(engine_android_path)
sidecar = read(sidecar_path)
runtime_ui = read(runtime_ui_path)
track_actions = read(track_actions_path)
settings = read(settings_path)
strings = read(strings_path)

# ---------------------------------------------------------------------------
# New common capability contract.
# Keeps PlayerEngineController itself identical to upstream.
# ---------------------------------------------------------------------------

contracts = r'''package com.nuvio.app.features.player

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

/**
 * Optional Android AutoSync capability layered beside PlayerEngineController.
 * Other platforms do not need to implement it.
 */
internal interface AutoSyncPlayerController {
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun runSubtitleAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}

internal fun PlayerEngineController.setAutoSyncSubtitleCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
) {
    (this as? AutoSyncPlayerController)?.setAutoSyncSubtitleCandidates(candidates)
}

internal fun PlayerEngineController.setSubtitleUriWithAutoSync(url: String) {
    val autoSync = this as? AutoSyncPlayerController
    if (autoSync != null) {
        autoSync.setSubtitleUriWithAutoSync(url)
    } else {
        setSubtitleUri(url)
    }
}

internal fun PlayerEngineController.runSubtitleAutoSync(url: String) {
    (this as? AutoSyncPlayerController)?.runSubtitleAutoSync(url)
}

internal fun PlayerEngineController.setAutoSyncAppliedListener(
    listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
) {
    (this as? AutoSyncPlayerController)?.setAutoSyncAppliedListener(listener)
}
'''
write(
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerAutoSyncContracts.kt",
    contracts,
)

engine_common = replace_once(
    engine_common,
    '''data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

''',
    "",
    "AutoSyncSubtitleCandidate in PlayerEngine.kt",
)
engine_common = replace_once(
    engine_common,
    '''    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) {}
    fun setSubtitleUriWithAutoSync(url: String) {
        setSubtitleUri(url)
        runSubtitleAutoSync(url)
    }
    fun runSubtitleAutoSync(url: String) {}
''',
    "",
    "AutoSync controller methods in PlayerEngine.kt",
)
engine_common = replace_once(
    engine_common,
    '''    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) {}
''',
    "",
    "AutoSync listener method in PlayerEngine.kt",
)

# ---------------------------------------------------------------------------
# New AutoSync-owned extractor factory helper.
# ---------------------------------------------------------------------------

extractor_helper = r'''package com.nuvio.app.features.autosync

import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

internal fun createAutoSyncExtractorsFactory(sourceKey: String): AutoSyncExtractorsFactory =
    AutoSyncExtractorsFactory(
        delegate = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE),
        sourceKey = sourceKey,
    )
'''
write(
    "composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutoSyncPlaybackExtractors.android.kt",
    extractor_helper,
)

# ---------------------------------------------------------------------------
# AutoSync-owned sidecar bridge.
# ---------------------------------------------------------------------------

sidecar_bridge = r'''package com.nuvio.app.features.autosync

import android.util.Log
import androidx.media3.common.C
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

private const val TAG = "NuvioAutoSyncSidecar"
private const val SIDECAR_WAIT_MS = 15_000L
private const val SIDECAR_WAIT_POLL_MS = 25L
private const val BOUNDARY_TOLERANCE_MS = 500L

internal suspend fun applyAutoSyncSidecarTimeline(
    sidecar: SidecarSubtitleController,
    url: String,
    timeline: AutoSyncTimelineRetimeResult,
): Boolean {
    if (!timeline.confident || sidecar.activeSidecarSubtitleKey != url) return false

    val current = sidecar.sidecarTimedCues.takeIf { it.isNotEmpty() } ?: withTimeoutOrNull(
        SIDECAR_WAIT_MS,
    ) {
        while (
            sidecar.activeSidecarSubtitleKey == url &&
            sidecar.sidecarTimedCues.isEmpty()
        ) {
            delay(SIDECAR_WAIT_POLL_MS)
        }
        sidecar.sidecarTimedCues.takeIf {
            sidecar.activeSidecarSubtitleKey == url && it.isNotEmpty()
        }
    } ?: return false

    val retimed = withContext(Dispatchers.Default) {
        retimeSidecarTimedCues(current, timeline)
    }
    return sidecar.commitPreparedSidecarSubtitle(
        expectedCurrentUrl = url,
        newUrl = url,
        cues = retimed,
    )
}

internal suspend fun replaceAutoSyncSidecarSubtitle(
    sidecar: SidecarSubtitleController,
    expectedCurrentUrl: String,
    url: String,
    headers: Map<String, String>,
    useLibass: Boolean,
    timeline: AutoSyncTimelineRetimeResult,
): Boolean {
    if (!timeline.confident) return false
    if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) return false
    if (sidecar.activeSidecarSubtitleKey != expectedCurrentUrl) return false

    val retimed = try {
        val rawBody = withContext(Dispatchers.IO) {
            httpGetTextWithHeaders(url = url, headers = headers)
        }
        val parsed = withContext(Dispatchers.Default) {
            parseSidecarTimedCuesRobust(rawBody, url).cues
        }
        if (parsed.isEmpty()) {
            Log.w(TAG, "replacement parse empty url=$url; keeping $expectedCurrentUrl")
            return false
        }
        withContext(Dispatchers.Default) {
            retimeSidecarTimedCues(parsed, timeline)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.w(
            TAG,
            "replacement preparation failed url=$url: ${error.message}; " +
                "keeping $expectedCurrentUrl",
        )
        return false
    }

    if (sidecar.activeSidecarSubtitleKey != expectedCurrentUrl) return false
    return sidecar.commitPreparedSidecarSubtitle(
        expectedCurrentUrl = expectedCurrentUrl,
        newUrl = url,
        cues = retimed,
    )
}

private data class TimingBoundary(
    val originalMs: Long,
    val retimedMs: Long,
)

private fun retimeSidecarTimedCues(
    source: List<CuesWithTiming>,
    timeline: AutoSyncTimelineRetimeResult,
): List<CuesWithTiming> {
    val boundaries = ArrayList<TimingBoundary>(timeline.cues.size * 2)
    timeline.cues.forEach { cue ->
        boundaries += TimingBoundary(cue.originalStartTimeMs, cue.startTimeMs)
        boundaries += TimingBoundary(cue.originalEndTimeMs, cue.endTimeMs)
    }
    boundaries.sortBy { it.originalMs }

    var boundaryMapped = 0
    var affineFallback = 0

    fun mapTime(originalMs: Long): Long {
        if (boundaries.isNotEmpty()) {
            var low = 0
            var high = boundaries.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (boundaries[mid].originalMs < originalMs) low = mid + 1 else high = mid
            }

            val first = (low - 2).coerceAtLeast(0)
            val last = (low + 2).coerceAtMost(boundaries.lastIndex)
            var best: TimingBoundary? = null
            var bestError = Long.MAX_VALUE
            if (first <= last) {
                for (index in first..last) {
                    val candidate = boundaries[index]
                    val error = kotlin.math.abs(candidate.originalMs - originalMs)
                    if (error < bestError) {
                        best = candidate
                        bestError = error
                    }
                }
            }
            if (best != null && bestError <= BOUNDARY_TOLERANCE_MS) {
                boundaryMapped++
                return best.retimedMs.coerceAtLeast(0L)
            }
        }

        affineFallback++
        return (
            originalMs.toDouble() * timeline.alignmentScale +
                timeline.alignmentInterceptMs
            ).roundToLong().coerceAtLeast(0L)
    }

    val out = ArrayList<CuesWithTiming>(source.size)
    source.forEach { entry ->
        if (entry.startTimeUs == C.TIME_UNSET) {
            out += entry
            return@forEach
        }

        val originalStartMs = entry.startTimeUs / 1_000L
        val originalEndMs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs / 1_000L
            entry.durationUs != C.TIME_UNSET ->
                originalStartMs + entry.durationUs / 1_000L
            else -> originalStartMs + 1L
        }.coerceAtLeast(originalStartMs + 1L)

        val startMs = mapTime(originalStartMs)
        val endMs = mapTime(originalEndMs).coerceAtLeast(startMs + 1L)
        out += CuesWithTiming(
            entry.cues,
            startMs * 1_000L,
            (endMs - startMs) * 1_000L,
        )
    }

    Log.d(
        TAG,
        "retime mapped sidecar=${source.size} " +
            "boundaryMapped=$boundaryMapped affineFallback=$affineFallback",
    )
    return out
}
'''
write(
    "composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutoSyncSidecarBridge.android.kt",
    sidecar_bridge,
)

# Sidecar: remove AutoSync implementation and leave a generic atomic commit hook.
for line in (
    "import com.nuvio.app.features.autosync.AutoSyncTimelineRetimeResult\n",
    "import kotlinx.coroutines.withTimeoutOrNull\n",
    "import kotlin.math.roundToLong\n",
):
    sidecar = sidecar.replace(line, "")

sidecar = sidecar.replace("private const val AUTO_SYNC_SIDECAR_WAIT_MS = 15_000L\n", "")
sidecar = sidecar.replace("private const val AUTO_SYNC_SIDECAR_WAIT_POLL_MS = 25L\n", "")
sidecar = sidecar.replace(
    'private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO\n',
    'private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO"\n',
)

auto_apply_start = sidecar.find(
    "    /**\n"
    "     * Atomically replaces only the timing of the currently parsed sidecar cues."
)
can_attach = sidecar.find(
    "    fun canAttachAddonSubtitleViaSidecar(url: String, useLibass: Boolean): Boolean {"
)
if auto_apply_start < 0 or can_attach < 0 or can_attach <= auto_apply_start:
    raise SystemExit("Could not isolate AutoSync sidecar apply method.")

generic_commit = r'''    /**
     * Commits already-prepared cues only if the expected subtitle is still active.
     */
    internal fun commitPreparedSidecarSubtitle(
        expectedCurrentUrl: String,
        newUrl: String,
        cues: List<CuesWithTiming>,
    ): Boolean {
        if (cues.isEmpty() || activeSidecarSubtitleKey != expectedCurrentUrl) return false

        if (newUrl != expectedCurrentUrl) {
            sidecarSubtitleJob?.cancel()
            activeSidecarSubtitleKey = newUrl
        }

        sidecarTimedCues = cues
        lastSidecarCueSignature = null
        postToSubtitleView { view ->
            view.setTag(R.id.player_view_sidecar_generation_tag, newUrl)
        }
        renderSidecarCuesAtCurrentPosition()

        if (newUrl != expectedCurrentUrl) {
            sidecarSubtitleJob = scope.launch {
                while (isActive && activeSidecarSubtitleKey == newUrl) {
                    renderSidecarCuesAtCurrentPosition()
                    delay(SIDECAR_RENDER_INTERVAL_MS)
                }
            }
        }
        return true
    }

'''
sidecar = sidecar[:auto_apply_start] + generic_commit + sidecar[can_attach:]

bottom_marker = "\nprivate const val AUTO_SYNC_BOUNDARY_TOLERANCE_MS = 500L\n"
bottom = sidecar.find(bottom_marker)
if bottom < 0:
    raise SystemExit("Could not find old sidecar AutoSync timing implementation.")
sidecar = sidecar[:bottom].rstrip() + "\n"
sidecar = sidecar.replace(
    "url=$url cues=${sidecarTimedCues.size} mime=${parseResult.effectiveMime}",
    "url=$url cues=${parseResult.cues.size} mime=${parseResult.effectiveMime}",
)

# ---------------------------------------------------------------------------
# New normal sidecar attach helper.
# ---------------------------------------------------------------------------

sidecar_selection = r'''@file:OptIn(androidx.media3.common.util.UnstableApi::class)

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
'''
write(
    "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSidecarSelection.android.kt",
    sidecar_selection,
)

# ---------------------------------------------------------------------------
# New AutoSync session coordinator.
# ---------------------------------------------------------------------------

coordinator = r'''@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.AutoSyncSubtitleCandidate
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

        val useLibass = getUseLibass()
        val subtitleHeaders = getSubtitleHeaders(url)
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            if (attachSubtitleOnReject) fallbackAttach(url)
            Toast.makeText(
                context,
                "Auto Sync V2: seamless retiming is unavailable for this subtitle renderer",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        if (!sidecar.startSidecarAddonSubtitle(url, subtitleHeaders, useLibass)) {
            if (attachSubtitleOnReject) fallbackAttach(url)
            Toast.makeText(
                context,
                "Auto Sync V2: subtitle could not be attached without reloading playback",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        job = scope.launch {
            Toast.makeText(
                context,
                "Auto Sync V2: building embedded timeline…",
                Toast.LENGTH_SHORT,
            ).show()

            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrl,
                sourceHeaders = sourceHeaders,
                selectedSubtitleUrl = url,
                selectedSubtitleHeaders = subtitleHeaders,
                preferredLanguage = getPreferredLanguage(),
                alternativeSubtitles = candidates,
                onReferenceReady = {
                    Toast.makeText(
                        context,
                        "Auto Sync V2: matching timelines…",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )

            if (resolved == null) {
                val copied = if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REJECT V2 - original sidecar timing kept",
                    )
                } else {
                    false
                }
                Toast.makeText(
                    context,
                    if (copied) {
                        "Auto Sync V2: no reliable match — original subtitle kept • debug log copied"
                    } else {
                        "Auto Sync V2: no reliable match — original subtitle kept"
                    },
                    Toast.LENGTH_LONG,
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
            } else {
                replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = url,
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
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
                    if (chosenUrl == url) {
                        "Auto Sync V2: match found but subtitle changed — original timing kept"
                    } else {
                        "Auto Sync V2: better subtitle could not be prepared — original subtitle kept"
                    },
                    Toast.LENGTH_LONG,
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
                if (chosenUrl == url) {
                    "Auto Sync V2: seamless match applied"
                } else {
                    "Auto Sync V2: switched to a better subtitle and synchronized it"
                },
                Toast.LENGTH_LONG,
            ).show()
            Log.i(
                TAG,
                "applied selected=$url chosen=$chosenUrl alignment=${timeline.alignmentSource}",
            )
        }
    }
}
'''
write(
    "composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutoSyncPlayerCoordinator.android.kt",
    coordinator,
)

# ---------------------------------------------------------------------------
# PlayerEngine.android.kt becomes hooks/delegation only.
# ---------------------------------------------------------------------------

for line in (
    "import android.widget.Toast\n",
    "import androidx.media3.extractor.DefaultExtractorsFactory\n",
    "import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory\n",
    "import androidx.media3.extractor.ts.TsExtractor\n",
    "import com.nuvio.app.features.autosync.AutoSyncDebugLog\n",
    "import com.nuvio.app.features.autosync.AutoSyncExtractorsFactory\n",
    "import com.nuvio.app.features.autosync.AutomaticSubtitleSync\n",
):
    engine_android = engine_android.replace(line, "")

insert_import_after = "import com.nuvio.app.R\n"
require(engine_android, insert_import_after, "PlayerEngine Android import insertion point")
engine_android = engine_android.replace(
    insert_import_after,
    insert_import_after +
    "import com.nuvio.app.features.autosync.AutoSyncPlayerCoordinator\n"
    "import com.nuvio.app.features.autosync.createAutoSyncExtractorsFactory\n",
    1,
)

engine_android = replace_once(
    engine_android,
    '''    val extractorsFactory = remember(sourceUrl, sourceAudioUrl) {
        AutoSyncExtractorsFactory(
            delegate = DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE),
            sourceKey = sourceUrl,
        )
    }
''',
    '''    val extractorsFactory = remember(sourceUrl, sourceAudioUrl) {
        createAutoSyncExtractorsFactory(sourceUrl)
    }
''',
    "extractors factory block",
)

engine_android = replace_once(
    engine_android,
    '''    var automaticSubtitleSyncJob by remember(playerSourceKey) { mutableStateOf<Job?>(null) }
    var autoSyncSubtitleCandidates by remember(playerSourceKey) {
        mutableStateOf<List<AutoSyncSubtitleCandidate>>(emptyList())
    }
    var autoSyncAppliedListener by remember {
        mutableStateOf<((subtitleUrl: String, delayMs: Int) -> Unit)?>(null)
    }
''',
    "",
    "AutoSync state in PlayerEngine.android.kt",
)

sidecar_controller_block = '''    val sidecarController = remember(exoPlayer, coroutineScope) {
        SidecarSubtitleController(
            scope = coroutineScope,
            getPlayer = { exoPlayer },
            getSubtitleDelayMs = { latestSubtitleDelayMs.value },
        )
    }

'''
require(engine_android, sidecar_controller_block, "sidecar controller block")
engine_android = engine_android.replace(
    sidecar_controller_block,
    sidecar_controller_block + r'''    val latestAutoSyncExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestAutoSyncUseLibass = rememberUpdatedState(useLibass)
    val latestAutoSyncPreferredLanguage =
        rememberUpdatedState(playerSettings.preferredSubtitleLanguage)
    val autoSyncCoordinator = remember(playerSourceKey, exoPlayer, sidecarController, coroutineScope) {
        AutoSyncPlayerCoordinator(
            context = context,
            scope = coroutineScope,
            player = exoPlayer,
            sidecar = sidecarController,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizedSourceHeaders,
            getSubtitleHeaders = { subtitleUrl ->
                latestAutoSyncExternalSubtitles.value
                    .firstOrNull { it.url == subtitleUrl }
                    ?.headers
                    .orEmpty()
            },
            getUseLibass = { latestAutoSyncUseLibass.value },
            getPreferredLanguage = { latestAutoSyncPreferredLanguage.value },
            onMimeTypeSelected = { selectedExternalSubtitleMimeType = it },
            onSubtitleDelayChanged = { subtitleDelayMs = it },
        )
    }

''',
    1,
)

engine_android = engine_android.replace(
    '''            automaticSubtitleSyncJob?.cancel()
            autoSyncAppliedListener = null
''',
    '''            autoSyncCoordinator.dispose()
''',
)
engine_android = engine_android.replace(
    "automaticSubtitleSyncJob?.cancel()",
    "autoSyncCoordinator.cancel()",
)

engine_android = replace_once(
    engine_android,
    '''    LaunchedEffect(exoPlayer) {
        onControllerReady(
            object : PlayerEngineController {
''',
    '''    LaunchedEffect(exoPlayer) {
        onControllerReady(
            object : PlayerEngineController, AutoSyncPlayerController {
''',
    "ExoPlayer controller declaration",
)

engine_android = replace_once(
    engine_android,
    '''                override fun setAutoSyncSubtitleCandidates(
                    candidates: List<AutoSyncSubtitleCandidate>,
                ) {
                    autoSyncSubtitleCandidates = candidates.distinctBy { it.url }
                }
''',
    '''                override fun setAutoSyncSubtitleCandidates(
                    candidates: List<AutoSyncSubtitleCandidate>,
                ) = autoSyncCoordinator.setCandidates(candidates)
''',
    "candidate delegation",
)

engine_android = replace_once(
    engine_android,
    '''                    if (sidecarController.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
                        Log.d(TAG, "setSubtitleUri: using buffer-preserving sidecar for url=$url")
                        val headers = externalSubtitles.firstOrNull { it.url == url }?.headers.orEmpty()
                        val attached = sidecarController.startSidecarAddonSubtitle(
                            url = url,
                            headers = headers,
                            useLibass = useLibass,
                        )
                        if (attached) {
                            selectedExternalSubtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(url)
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            return
                        }
                    }

''',
    '''                    val sidecarHeaders =
                        externalSubtitles.firstOrNull { it.url == url }?.headers.orEmpty()
                    if (
                        tryAttachExternalSubtitleSidecar(
                            player = exoPlayer,
                            sidecar = sidecarController,
                            url = url,
                            headers = sidecarHeaders,
                            useLibass = useLibass,
                            onMimeTypeSelected = { selectedExternalSubtitleMimeType = it },
                        )
                    ) {
                        return
                    }

''',
    "normal sidecar attach block",
)

start = engine_android.find("                private fun startSubtitleAutoSync(\n")
end = engine_android.find(
    "                override fun setSubtitleUriWithAutoSync(url: String) {",
    start,
)
if start < 0 or end < 0:
    raise SystemExit("Could not isolate startSubtitleAutoSync().")
engine_android = engine_android[:start] + engine_android[end:]

engine_android = replace_once(
    engine_android,
    '''                override fun setSubtitleUriWithAutoSync(url: String) {
                    startSubtitleAutoSync(url = url, attachSubtitleOnReject = true)
                }

                override fun runSubtitleAutoSync(url: String) {
                    startSubtitleAutoSync(url = url, attachSubtitleOnReject = false)
                }
''',
    '''                override fun setSubtitleUriWithAutoSync(url: String) {
                    autoSyncCoordinator.start(
                        url = url,
                        attachSubtitleOnReject = true,
                        fallbackAttach = { setSubtitleUri(it) },
                    )
                }

                override fun runSubtitleAutoSync(url: String) {
                    autoSyncCoordinator.start(
                        url = url,
                        attachSubtitleOnReject = false,
                        fallbackAttach = {},
                    )
                }
''',
    "AutoSync start overrides",
)

engine_android = replace_once(
    engine_android,
    '''                override fun setAutoSyncAppliedListener(
                    listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
                ) {
                    autoSyncAppliedListener = listener
                }
''',
    '''                override fun setAutoSyncAppliedListener(
                    listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
                ) = autoSyncCoordinator.setAppliedListener(listener)
''',
    "AutoSync listener delegation",
)

# ---------------------------------------------------------------------------
# Runtime AutoSync effects/startup decisions in a new file.
# ---------------------------------------------------------------------------

runtime_autosync = r'''package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository

private fun PlayerScreenRuntime.currentAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    addonSubtitles.map { subtitle ->
        AutoSyncSubtitleCandidate(
            url = subtitle.url,
            language = subtitle.language,
            name = subtitle.display,
        )
    }

internal fun PlayerScreenRuntime.configureAutoSyncController(
    controller: PlayerEngineController,
) {
    controller.setAutoSyncSubtitleCandidates(currentAutoSyncCandidates())
    controller.setAutoSyncAppliedListener { subtitleUrl, delayMs ->
        val appliedSubtitle = addonSubtitles.firstOrNull { it.url == subtitleUrl }
        selectedAddonSubtitleId = appliedSubtitle?.selectionKey ?: subtitleUrl
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        preferredSubtitleSelectionApplied = true
        if (appliedSubtitle != null) {
            persistAddonSubtitlePreference(appliedSubtitle)
        }

        val appliedDelayMs = delayMs.coerceIn(
            SUBTITLE_DELAY_MIN_MS,
            SUBTITLE_DELAY_MAX_MS,
        )
        subtitleDelayMs = appliedDelayMs
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(
            playbackSession.videoId,
            appliedDelayMs,
        )
    }
}

@Composable
internal fun PlayerScreenRuntime.BindAutoSyncRuntimeEffects() {
    LaunchedEffect(playerController, addonSubtitles) {
        playerController?.setAutoSyncSubtitleCandidates(currentAutoSyncCandidates())
    }
}

internal fun PlayerScreenRuntime.runSelectedAddonAutoSync() {
    selectedAddonSubtitle?.let { subtitle ->
        playerController?.runSubtitleAutoSync(subtitle.url)
    }
}

internal fun PlayerScreenRuntime.maybeAutoSyncRestoredSubtitleAtStart(url: String): Boolean {
    val controller = playerController ?: return false
    val videoKey = activeVideoId?.takeIf { it.isNotBlank() } ?: activeSourceUrl
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), videoKey)) return false
    controller.setSubtitleUriWithAutoSync(url)
    return true
}

internal fun PlayerScreenRuntime.maybeAutoSyncPreferredSubtitleAtStart(
    subtitle: AddonSubtitle,
): Boolean {
    if (isUserExplicitSubtitleSelection) return false
    val preferredLanguage =
        normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) ?: return false
    if (
        preferredLanguage.isBlank() ||
        preferredLanguage == SubtitleLanguageOption.NONE ||
        preferredLanguage == SubtitleLanguageOption.FORCED
    ) {
        return false
    }

    val controller = playerController ?: return false
    val videoKey = activeVideoId?.takeIf { it.isNotBlank() } ?: activeSourceUrl
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), videoKey)) return false
    controller.setSubtitleUriWithAutoSync(subtitle.url)
    return true
}
'''
write(
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerRuntimeAutoSync.kt",
    runtime_autosync,
)

track_actions = track_actions.replace(
    "import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository\n\n",
    "",
)
helper_start = track_actions.find(
    "\n\nprivate fun PlayerScreenRuntime.maybeAutoSyncRestoredSubtitleAtStart"
)
disable_start = track_actions.find(
    "\nprivate fun PlayerScreenRuntime.disableAutomaticSubtitleSelection()",
    helper_start,
)
if helper_start < 0 or disable_start < 0:
    raise SystemExit("Could not isolate startup AutoSync helpers in track actions.")
track_actions = track_actions[:helper_start] + "\n" + track_actions[disable_start + 1:]

runtime_ui = replace_once(
    runtime_ui,
    '''    LaunchedEffect(playerController, addonSubtitles) {
        playerController?.setAutoSyncSubtitleCandidates(
            addonSubtitles.map { subtitle ->
                AutoSyncSubtitleCandidate(
                    url = subtitle.url,
                    language = subtitle.language,
                    name = subtitle.display,
                )
            },
        )
    }

''',
    "    BindAutoSyncRuntimeEffects()\n\n",
    "candidate feed effect in runtime UI",
)

runtime_ui = replace_once(
    runtime_ui,
    '''                onControllerReady = { controller ->
                    playerController = controller
                    playerControllerSourceUrl = activeSourceUrl
                    controller.setAutoSyncAppliedListener { subtitleUrl, delayMs ->
                        val appliedSubtitle = addonSubtitles.firstOrNull { it.url == subtitleUrl }
                        selectedAddonSubtitleId = appliedSubtitle?.selectionKey ?: subtitleUrl
                        selectedSubtitleIndex = -1
                        useCustomSubtitles = true
                        preferredSubtitleSelectionApplied = true
                        if (appliedSubtitle != null) {
                            persistAddonSubtitlePreference(appliedSubtitle)
                        }

                        val appliedDelayMs = delayMs.coerceIn(
                            SUBTITLE_DELAY_MIN_MS,
                            SUBTITLE_DELAY_MAX_MS,
                        )
                        subtitleDelayMs = appliedDelayMs
                        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(
                            playbackSession.videoId,
                            appliedDelayMs,
                        )
                    }
                },
''',
    '''                onControllerReady = { controller ->
                    playerController = controller
                    playerControllerSourceUrl = activeSourceUrl
                    configureAutoSyncController(controller)
                },
''',
    "AutoSync applied listener in onControllerReady",
)

runtime_ui = replace_once(
    runtime_ui,
    '''        onAutomaticAutoSync = {
            selectedAddonSubtitle?.let { subtitle ->
                playerController?.runSubtitleAutoSync(subtitle.url)
            }
        },
''',
    '''        onAutomaticAutoSync = { runSelectedAddonAutoSync() },
''',
    "manual AutoSync callback",
)

if runtime_ui.count("LaunchedEffect") == 1:
    runtime_ui = runtime_ui.replace("import androidx.compose.runtime.LaunchedEffect\n", "")

# ---------------------------------------------------------------------------
# Settings rows into a new file.
# ---------------------------------------------------------------------------

settings_rows = r'''package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_debug_logs
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_debug_logs_description
import nuvio.composeapp.generated.resources.settings_playback_subtitle_auto_sync
import nuvio.composeapp.generated.resources.settings_playback_subtitle_auto_sync_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoSyncPlaybackSettingsRows(
    isTablet: Boolean,
    enabled: Boolean,
    preferredSubtitleLanguage: String,
) {
    val preferredSubtitleAutoSyncOnStart by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart
    }.collectAsStateWithLifecycle()
    val debugLogsEnabled by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.debugLogsEnabled
    }.collectAsStateWithLifecycle()

    if (isIos) return

    val preferredLanguageAvailable =
        preferredSubtitleLanguage.isNotBlank() &&
            preferredSubtitleLanguage != SubtitleLanguageOption.NONE &&
            preferredSubtitleLanguage != SubtitleLanguageOption.FORCED

    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_subtitle_auto_sync),
        description = stringResource(
            Res.string.settings_playback_subtitle_auto_sync_description,
        ),
        checked = preferredSubtitleAutoSyncOnStart,
        enabled = enabled && preferredLanguageAvailable,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setPreferredSubtitleAutoSyncOnStart,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_auto_sync_debug_logs),
        description = stringResource(
            Res.string.settings_playback_auto_sync_debug_logs_description,
        ),
        checked = debugLogsEnabled,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setDebugLogsEnabled,
    )
}
'''
write(
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AutoSyncPlaybackSettings.kt",
    settings_rows,
)

settings = settings.replace(
    "import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository\n",
    "",
)
settings = replace_once(
    settings,
    '''    val preferredSubtitleAutoSyncOnStart by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart
    }.collectAsStateWithLifecycle()
    val autoSyncDebugLogsEnabled by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.debugLogsEnabled
    }.collectAsStateWithLifecycle()
''',
    "",
    "AutoSync state collection in PlaybackSettingsPage",
)
settings = replace_once(
    settings,
    '''            val autoSyncPreferredLanguageAvailable =
                preferredSubtitleLanguage.isNotBlank() &&
                    preferredSubtitleLanguage != SubtitleLanguageOption.NONE &&
                    preferredSubtitleLanguage != SubtitleLanguageOption.FORCED

''',
    "",
    "AutoSync preferred language availability block",
)
settings = replace_once(
    settings,
    '''                if (!isIos) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_subtitle_auto_sync),
                        description = stringResource(Res.string.settings_playback_subtitle_auto_sync_description),
                        checked = preferredSubtitleAutoSyncOnStart,
                        enabled = otherSubtitleOptionsEnabled && autoSyncPreferredLanguageAvailable,
                        isTablet = isTablet,
                        onCheckedChange = AutoSyncPreferencesRepository::setPreferredSubtitleAutoSyncOnStart,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_auto_sync_debug_logs),
                        description = stringResource(Res.string.settings_playback_auto_sync_debug_logs_description),
                        checked = autoSyncDebugLogsEnabled,
                        enabled = otherSubtitleOptionsEnabled,
                        isTablet = isTablet,
                        onCheckedChange = AutoSyncPreferencesRepository::setDebugLogsEnabled,
                    )
                }
''',
    '''                AutoSyncPlaybackSettingsRows(
                    isTablet = isTablet,
                    enabled = otherSubtitleOptionsEnabled,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                )
''',
    "AutoSync settings rows",
)

for line in (
    '    <string name="settings_playback_subtitle_auto_sync">Auto Sync Subtitle</string>\n',
    '    <string name="settings_playback_subtitle_auto_sync_description">Automatically sync the preferred add-on subtitle when playback starts. Requires a preferred subtitle language.</string>\n',
    '    <string name="settings_playback_auto_sync_debug_logs">Debug Logs for Auto Sync</string>\n',
    '    <string name="settings_playback_auto_sync_debug_logs_description">Keep verbose Auto Sync logs and copy the report to the clipboard after each run.</string>\n',
):
    require(strings, line, "AutoSync resource string")
    strings = strings.replace(line, "", 1)

write(
    "composeApp/src/commonMain/composeResources/values/autosync_strings.xml",
    '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="settings_playback_subtitle_auto_sync">Auto Sync Subtitle</string>
    <string name="settings_playback_subtitle_auto_sync_description">Automatically sync the preferred add-on subtitle when playback starts. Requires a preferred subtitle language.</string>
    <string name="settings_playback_auto_sync_debug_logs">Debug Logs for Auto Sync</string>
    <string name="settings_playback_auto_sync_debug_logs_description">Keep verbose Auto Sync logs and copy the report to the clipboard after each run.</string>
</resources>
''',
)

write(engine_common_path, engine_common)
write(engine_android_path, engine_android)
write(sidecar_path, sidecar)
write(runtime_ui_path, runtime_ui)
write(track_actions_path, track_actions)
write(settings_path, settings)
write(strings_path, strings)

checks = [
    ("PlayerEngine.kt no AutoSync API", "AutoSync" not in engine_common),
    ("PlayerEngine Android coordinator", "AutoSyncPlayerCoordinator(" in engine_android),
    ("old orchestration removed", "private fun startSubtitleAutoSync" not in engine_android),
    ("sidecar mapping removed", "AUTO_SYNC_BOUNDARY_TOLERANCE_MS" not in sidecar),
    ("sidecar generic commit exists", "commitPreparedSidecarSubtitle" in sidecar),
    ("runtime UI candidate details removed", "AutoSyncSubtitleCandidate(" not in runtime_ui),
    ("track actions preferences moved", "AutoSyncPreferencesRepository" not in track_actions),
    ("settings preferences moved", "AutoSyncPreferencesRepository" not in settings),
]
failed = [name for name, ok in checks if not ok]
if failed:
    raise SystemExit("Static refactor checks failed: " + ", ".join(failed))

subprocess.run(["git", "diff", "--check"], check=True)

print("Minimal-hook AutoSync refactor applied.")
print("Matching algorithms and thresholds were not changed.")
print("Includes transactional/fallback-safe alternative subtitle replacement.")
print()
subprocess.run(["git", "diff", "--stat"], check=True)
print()
print("Verify with:")
print("./gradlew :composeApp:testAndroidHostTest "
      "-Pnuvio.android.distribution=full "
      '--tests "com.nuvio.app.features.autosync.AutoSyncTimelineRetimeTest" --stacktrace')
print("./gradlew :composeApp:assembleFullDebug -Pnuvio.android.distribution=full --stacktrace")
