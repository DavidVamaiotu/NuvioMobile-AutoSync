#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

ROOT = Path.cwd()
if not (ROOT / "composeApp").exists():
    raise SystemExit("Run this from the root of NuvioMobile-AutoSync.")

branch = subprocess.check_output(
    ["git", "rev-parse", "--abbrev-ref", "HEAD"], text=True
).strip()
if branch != "autosync-v2-timeline":
    raise SystemExit(f"Wrong branch: {branch}. Switch to autosync-v2-timeline first.")

if subprocess.run(["git", "diff", "--quiet"]).returncode != 0:
    raise SystemExit("Tracked working-tree changes exist. Commit/stash them first.")
if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode != 0:
    raise SystemExit("Staged changes exist. Commit/stash them first.")

sidecar_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSidecarSubtitles.kt"
engine_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt"
libass_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerLibassCompat.kt"
auto_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutomaticSubtitleSync.kt"
native_parser_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutoSyncNativeSubtitleParser.kt"

for p in (sidecar_path, engine_path, libass_path, auto_path):
    if not p.exists():
        raise SystemExit(f"Missing expected file: {p}")

sidecar = sidecar_path.read_text()
marker = "private fun retimeSidecarTimedCues("
idx = sidecar.find(marker)
if idx < 0:
    raise SystemExit("Could not find retimeSidecarTimedCues() in PlayerSidecarSubtitles.kt")

new_retime = r'''private const val AUTO_SYNC_BOUNDARY_TOLERANCE_MS = 500L

private data class AutoSyncTimingBoundary(
    val originalMs: Long,
    val retimedMs: Long,
)

private fun retimeSidecarTimedCues(
    source: List<CuesWithTiming>,
    timeline: AutoSyncTimelineRetimeResult,
): List<CuesWithTiming> {
    val boundaries = ArrayList<AutoSyncTimingBoundary>(timeline.cues.size * 2)
    timeline.cues.forEach { cue ->
        boundaries += AutoSyncTimingBoundary(cue.originalStartTimeMs, cue.startTimeMs)
        boundaries += AutoSyncTimingBoundary(cue.originalEndTimeMs, cue.endTimeMs)
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
            var best: AutoSyncTimingBoundary? = null
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
            if (best != null && bestError <= AUTO_SYNC_BOUNDARY_TOLERANCE_MS) {
                boundaryMapped += 1
                return best.retimedMs.coerceAtLeast(0L)
            }
        }

        affineFallback += 1
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
        SIDECAR_TAG,
        "AutoSync V2 retime mapped sidecar=${source.size} " +
            "boundaryMapped=$boundaryMapped affineFallback=$affineFallback",
    )
    return out
}
'''

sidecar = sidecar[:idx] + new_retime
sidecar = sidecar.replace("import kotlin.math.abs\n", "import kotlin.math.roundToLong\n")
sidecar = sidecar.replace(
    '''     * Cue text/spans/positioning remain the Media3-parsed originals. A parser-sequence mismatch
     * refuses V2 so the caller can use the existing delay-based AutoSync unchanged.
''',
    '''     * Cue text/spans/positioning remain the Media3-parsed originals. Media3 event boundaries are
     * mapped onto V2's corrected cue boundaries, with the accepted affine alignment as fallback.
''',
)

old_apply = "val retimed = retimeSidecarTimedCues(current, timeline) ?: return false"
if old_apply not in sidecar:
    raise SystemExit("Expected strict sidecar apply line was not found.")
sidecar = sidecar.replace(
    old_apply,
    "val retimed = retimeSidecarTimedCues(current, timeline)",
    1,
)

if "using V1 fallback" in sidecar:
    raise SystemExit("Stale V1 fallback text remains in sidecar patch.")
if "import kotlin.math.roundToLong" not in sidecar:
    raise SystemExit("roundToLong import was not installed.")

engine = engine_path.read_text()
engine = engine.replace(
    "import com.nuvio.app.features.autosync.AutoSyncNativeSubtitleTimelines\n", ""
)
engine = engine.replace(
    "import com.nuvio.app.features.autosync.AutoSyncSubtitleParserFactory\n", ""
)
engine = engine.replace(
    "    val autoSyncSubtitleParserFactory = remember { AutoSyncSubtitleParserFactory() }\n\n",
    "",
)
engine = re.sub(
    r"\n\s*\.setSubtitleParserFactory\(autoSyncSubtitleParserFactory\)",
    "",
    engine,
)
engine = engine.replace(
    ").setSubtitleParserFactory(autoSyncSubtitleParserFactory)",
    ")",
)
engine = engine.replace(
    "    var autoSyncApplyingSubtitleUrl by remember(playerSourceKey) { mutableStateOf<String?>(null) }\n",
    "",
)

set_marker = "                override fun setSubtitleUri(url: String) {"
auto_marker = "                private fun startSubtitleAutoSync("
first_set = engine.find(set_marker)
start_auto = engine.find(auto_marker, first_set)
if first_set < 0 or start_auto < 0:
    raise SystemExit("Could not isolate ExoPlayer setSubtitleUri()/startSubtitleAutoSync().")

new_set = r'''                override fun setSubtitleUri(url: String) {
                    Log.d(TAG, "setSubtitleUri: url=$url")
                    subtitleSelectionJob?.cancel()
                    automaticSubtitleSyncJob?.cancel()

                    if (sidecarController.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
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

                    sidecarController.stopSidecarAddonSubtitle(clearView = true)
                    subtitleSelectionJob = coroutineScope.launch {
                        val currentPosition = exoPlayer.currentPosition
                        val wasPlaying = exoPlayer.isPlaying
                        val currentMediaItem = exoPlayer.currentMediaItem ?: run {
                            Log.e(TAG, "setSubtitleUri: currentMediaItem is null, aborting")
                            return@launch
                        }
                        preserveAudioSelectionForReload("setSubtitleUri")
                        val resolvedMime = PlayerSubtitleUtils.mimeTypeFromUrl(url)
                        selectedExternalSubtitleMimeType = resolvedMime
                        Log.d(TAG, "setSubtitleUri: currentPosition=$currentPosition, wasPlaying=$wasPlaying")
                        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                            .setMimeType(resolvedMime)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build()
                        Log.d(
                            TAG,
                            "setSubtitleUri: subtitleConfig built, uri=${subtitleConfig.uri}, mime=${subtitleConfig.mimeType}, selectionFlags=${subtitleConfig.selectionFlags}"
                        )
                        val newMediaItem = currentMediaItem.buildUpon()
                            .setSubtitleConfigurations(listOf(subtitleConfig))
                            .build()
                        Log.d(TAG, "setSubtitleUri: newMediaItem subtitleConfigs count=${newMediaItem.localConfiguration?.subtitleConfigurations?.size}")
                        val currentTextFlags =
                            exoPlayer.trackSelectionParameters.ignoredTextSelectionFlags
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setIgnoredTextSelectionFlags(
                                currentTextFlags and C.SELECTION_FLAG_DEFAULT.inv()
                            )
                            .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build()
                        Log.d(TAG, "setSubtitleUri: track params set before prepare, textDisabled=${exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
                        exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = wasPlaying
                        Log.d(TAG, "setSubtitleUri: prepare() called, waiting for STATE_READY")
                    }
                }

'''

engine = engine[:first_set] + new_set + engine[start_auto:]

start_auto = engine.find(auto_marker)
end_auto = engine.find(
    "                override fun setSubtitleUriWithAutoSync", start_auto
)
if start_auto < 0 or end_auto < 0:
    raise SystemExit("Could not isolate startSubtitleAutoSync().")

new_auto = r'''                private fun startSubtitleAutoSync(
                    url: String,
                    attachSubtitleOnReject: Boolean,
                ) {
                    automaticSubtitleSyncJob?.cancel()
                    val subtitleHeaders =
                        externalSubtitles.firstOrNull { it.url == url }?.headers.orEmpty()

                    if (!sidecarController.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
                        if (attachSubtitleOnReject) {
                            setSubtitleUri(url)
                        }
                        Toast.makeText(
                            context,
                            "Auto Sync V2: seamless retiming is unavailable for this subtitle renderer",
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }

                    val attached = sidecarController.startSidecarAddonSubtitle(
                        url = url,
                        headers = subtitleHeaders,
                        useLibass = useLibass,
                    )
                    if (!attached) {
                        if (attachSubtitleOnReject) {
                            setSubtitleUri(url)
                        }
                        Toast.makeText(
                            context,
                            "Auto Sync V2: subtitle could not be attached without reloading playback",
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }

                    selectedExternalSubtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(url)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()

                    automaticSubtitleSyncJob = coroutineScope.launch {
                        Toast.makeText(
                            context,
                            "Auto Sync V2: building embedded timeline…",
                            Toast.LENGTH_SHORT,
                        ).show()

                        val timeline = AutomaticSubtitleSync.findTimelineRetime(
                            sourceKey = sourceUrl,
                            sourceHeaders = sanitizedSourceHeaders,
                            selectedSubtitleUrl = url,
                            selectedSubtitleHeaders = subtitleHeaders,
                            preferredLanguage = playerSettings.preferredSubtitleLanguage,
                            onReferenceReady = {
                                Toast.makeText(
                                    context,
                                    "Auto Sync V2: matching full timelines…",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )

                        if (timeline == null) {
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

                        val applied = sidecarController.applyAutoSyncTimeline(url, timeline)
                        if (!applied) {
                            if (AutoSyncDebugLog.ENABLED) {
                                AutoSyncDebugLog.finishAndCopy(
                                    context = context,
                                    decision = "REJECT V2 - sidecar changed or was unavailable before apply",
                                )
                            }
                            Toast.makeText(
                                context,
                                "Auto Sync V2: match found but subtitle changed — original timing kept",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }

                        subtitleDelayMs = 0
                        autoSyncAppliedListener?.invoke(url, 0)

                        AutoSyncDebugLog.info {
                            "AUTO APPLY V2 sidecar=true bufferPreserved=true groups=${timeline.groups.size} " +
                                "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                                "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)} finalDelay=0ms"
                        }
                        if (AutoSyncDebugLog.ENABLED) {
                            AutoSyncDebugLog.finishAndCopy(
                                context = context,
                                decision =
                                    "APPLIED V2 sidecar timeline bufferPreserved=true url=$url groups=${timeline.groups.size} " +
                                        "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                                        "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)}",
                            )
                        }
                        Toast.makeText(
                            context,
                            "Auto Sync V2: seamless timeline matched • ${"%.0f".format(timeline.targetCoverage * 100.0)}%",
                            Toast.LENGTH_LONG,
                        ).show()
                        Log.i(
                            TAG,
                            "Automatic subtitle V2 applied through buffer-preserving sidecar url=$url " +
                                "groups=${timeline.groups.size} finalDelay=0ms",
                        )
                    }
                }

'''

engine = engine[:start_auto] + new_auto + engine[end_auto:]

for forbidden in (
    "AutoSyncNativeSubtitleTimelines",
    "AutoSyncSubtitleParserFactory",
    "autoSyncApplyingSubtitleUrl",
    "autoSyncSubtitleParserFactory",
):
    if forbidden in engine:
        raise SystemExit(
            f"Native parser integration still present in PlayerEngine.android.kt: {forbidden}"
        )

if "AUTO APPLY V2 sidecar=true bufferPreserved=true" not in engine:
    raise SystemExit("Sidecar AutoSync apply path was not installed.")

libass = libass_path.read_text()
libass = libass.replace(
    "import com.nuvio.app.features.autosync.AutoSyncSubtitleParserFactory\n", ""
)
old_libass = '''    val assSubtitleParserFactory =
        AutoSyncSubtitleParserFactory(CompatAssSubtitleParserFactory(assHandler))
'''
if old_libass not in libass:
    raise SystemExit("Expected AutoSync libass wrapper was not found.")
libass = libass.replace(
    old_libass,
    "    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)\n",
    1,
)
if "AutoSyncSubtitleParserFactory" in libass:
    raise SystemExit("AutoSync parser wrapper still remains in PlayerLibassCompat.kt.")

auto = auto_path.read_text()
old_log = "V2 direct timeline accepted selected subtitle; native Media3 parser retiming is ready"
new_log = "V2 direct timeline accepted selected subtitle; corrected timeline is ready for sidecar apply"
if old_log not in auto:
    raise SystemExit("Expected native Media3 recommendation log was not found.")
auto = auto.replace(old_log, new_log, 1)

sidecar_path.write_text(sidecar)
engine_path.write_text(engine)
libass_path.write_text(libass)
auto_path.write_text(auto)

if native_parser_path.exists():
    native_parser_path.unlink()

subprocess.run(["git", "diff", "--check"], check=True)

print("Seamless AutoSync sidecar patch applied successfully.")
print("V2 algorithm file AutoSyncTimelineRetime.kt was not modified.")
print()
subprocess.run(["git", "diff", "--stat"], check=True)
