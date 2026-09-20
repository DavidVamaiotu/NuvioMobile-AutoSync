package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lightweight, text-independent full-timeline subtitle retiming.
 *
 * The existing AutoSync matcher supplies a coarse affine transform that puts an add-on subtitle
 * near an embedded subtitle timeline. This aligner then walks both ordered cue sequences and
 * resolves local 1:1 / 1:2 / 2:1 / 1:3 / 3:1 / 2:2 groupings plus skips. Matched add-on groups
 * are locally anchored to embedded starts while preserving the add-on cue durations; unmatched
 * add-on cues retain the coarse affine timing.
 *
 * This file is deliberately commonMain and player-independent so the algorithm can be unit-tested
 * without Android, Media3, networking, or playback state.
 */
internal object AutoSyncTimelineRetimer {
    private const val MIN_CUES = 4
    private const val BAND_RADIUS_CUES = 28

    private const val SKIP_REFERENCE_COST = 1.35
    private const val SKIP_TARGET_COST = 1.85
    private const val GROUP_COMPLEXITY_COST = 0.10

    private const val START_END_TOLERANCE_MS = 1_250.0
    private const val MIDPOINT_TOLERANCE_MS = 1_500.0
    private const val DURATION_TOLERANCE_MS = 2_000.0
    private const val MAX_GROUP_COST = 8.0

    private const val MIN_TARGET_COVERAGE = 0.84
    private const val MIN_MATCHED_TARGET_CUES = 20
    private const val MAX_AVERAGE_GROUP_COST = 2.35
    private const val MAX_LONGEST_TARGET_SKIP_RUN = 12

    // Whole-timeline subtitle activity correlation. This is deliberately global:
    // mid-film cuts/splits are rejected rather than growing another piecewise synchronization layer.
    private const val ACTIVITY_COARSE_BIN_MS = 500L
    private const val ACTIVITY_FINE_BIN_MS = 100L
    private const val ACTIVITY_MAX_OFFSET_MS = 180_000L
    private const val ACTIVITY_FINE_RADIUS_MS = 750L
    private const val ACTIVITY_MAX_CUE_DURATION_MS = 20_000L
    private const val ACTIVITY_MAX_TIMELINE_MS = 8L * 60L * 60L * 1_000L
    private const val ACTIVITY_MIN_SCORE = 0.55
    private const val ACTIVITY_MIN_MARGIN = 0.02
    private const val ACTIVITY_DISTINCT_TRANSFORM_MS = 3_000.0
    private const val ACTIVITY_MIN_SCALE = 0.94
    private const val ACTIVITY_MAX_SCALE = 1.06
    private const val ACTIVITY_SCALE_DEDUP = 0.00035

    // Cheap delay-only fast path. It always APPLIES scale=1.0; the segment drift tolerance
    // merely allows near-1.0 timelines to qualify when one constant delay remains visually valid.
    private const val DELAY_ONLY_MIN_CUES = 4
    private const val DELAY_ONLY_SCALE_TOLERANCE = 0.0015
    private const val DELAY_ONLY_MIN_SCORE = 0.78
    private const val DELAY_ONLY_MIN_MARGIN = 0.02
    private const val DELAY_ONLY_MIN_SEGMENT_SCORE = 0.68
    private const val DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS = 1_000L
    private const val DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS = 500L
    private const val DELAY_ONLY_DISTINCT_OFFSET_MS = 3_000L
    private const val SMALL_SAMPLE_CUE_LIMIT = 8
    private const val SMALL_SAMPLE_ACTIVITY_MIN_SCORE = 0.72
    private const val SMALL_SAMPLE_ACTIVITY_MIN_MARGIN = 0.03
    private const val SMALL_SAMPLE_REQUIRED_COVERAGE_SEGMENTS = 2

    // Activity correlation only finds the global corridor. The existing cue/group DP remains
    // the final authority before embedded timestamps can replace external timing.
    private const val DISCOVERED_MIN_TARGET_COVERAGE = 0.90
    private const val DISCOVERED_MAX_AVERAGE_GROUP_COST = 1.10
    private const val DISCOVERED_MIN_SIMPLE_GROUP_RATIO = 0.55
    private const val SEGMENTATION_IMBALANCE_RATIO = 1.60
    private const val SEGMENTATION_MIN_TIMELINE_COVERAGE = 0.80
    private const val COVERAGE_SEGMENT_MIN_COVERAGE = 0.72
    private const val COVERAGE_SEGMENT_MAX_AVERAGE_COST = 1.35
    private const val COVERAGE_SEGMENT_MIN_GROUPS = 4

    private val groupShapes = arrayOf(
        GroupShape(referenceCount = 1, targetCount = 1),
        GroupShape(referenceCount = 1, targetCount = 2),
        GroupShape(referenceCount = 2, targetCount = 1),
        GroupShape(referenceCount = 1, targetCount = 3),
        GroupShape(referenceCount = 3, targetCount = 1),
        GroupShape(referenceCount = 2, targetCount = 2),
    )

    internal fun normalizeExternalTimeline(
        cues: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues

        val sorted = cues.sortedBy { it.startTimeMs }
        val seen = HashSet<ExternalCueKey>()
        val normalized = ArrayList<SubtitleSyncCue>(sorted.size)
        for (cue in sorted) {
            val key = ExternalCueKey(
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.endTimeMs,
                text = cue.text,
            )
            if (seen.add(key)) normalized += cue
        }
        return normalized
    }

    fun retime(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
        discoverAlignment: Boolean = false,
        allowAmbiguousDelayOnlyMargin: Boolean = false,
        referenceEstimatedEndStartsMs: Set<Long> = emptySet(),
    ): AutoSyncTimelineRetimeResult? {
        if (!discoverAlignment) {
            val result = retimeWithSeed(
                reference = reference,
                target = target,
                coarseScale = coarseScale,
                coarseInterceptMs = coarseInterceptMs,
                referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
            ) ?: return null
            return result.copy(
                alignmentSource = "provided",
                alignmentScale = coarseScale,
                alignmentInterceptMs = coarseInterceptMs,
                coverageSegmentsPassed = coverageSegmentsPassed(result, target.size),
                simpleGroupRatio = structuralGroupRatio(
                    result = result,
                    referenceSize = reference.size,
                    targetSize = target.size,
                ),
            )
        }

        val alignment = discoverActivityAlignment(reference, target) ?: return null

        // Delay-only remains the cheap candidate finder, but it must now pass through
        // the normal V2 cue/group validator before its correction can be applied.
        val delayOnly = if (abs(alignment.scale - 1.0) <= DELAY_ONLY_SCALE_TOLERANCE) {
            findDelayOnlyAlignment(
                reference = reference,
                target = target,
                allowAmbiguousMargin = allowAmbiguousDelayOnlyMargin,
            )
        } else {
            null
        }

        val candidateScale = if (delayOnly != null) 1.0 else alignment.scale
        val candidateInterceptMs = delayOnly?.offsetMs ?: alignment.interceptMs
        val candidateActivityScore = delayOnly?.score ?: alignment.score
        val candidateActivityMargin = delayOnly?.margin ?: alignment.margin

        val result = retimeWithSeed(
            reference = reference,
            target = target,
            coarseScale = candidateScale,
            coarseInterceptMs = candidateInterceptMs,
            referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
        ) ?: return null

        val coverageSegments = coverageSegmentsPassed(result, target.size)
        val simpleRatio = structuralGroupRatio(
            result = result,
            referenceSize = reference.size,
            targetSize = target.size,
        )
        val smallSample = target.size < SMALL_SAMPLE_CUE_LIMIT
        val requiredActivityScore =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_SCORE else ACTIVITY_MIN_SCORE
        val requiredActivityMargin =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_MARGIN else ACTIVITY_MIN_MARGIN
        val requiredCoverageSegments =
            if (smallSample) SMALL_SAMPLE_REQUIRED_COVERAGE_SEGMENTS else 3
        val activityMarginAccepted =
            candidateActivityMargin >= requiredActivityMargin ||
                (delayOnly != null && allowAmbiguousDelayOnlyMargin)

        val confirmed =
            result.confident &&
                candidateActivityScore >= requiredActivityScore &&
                activityMarginAccepted &&
                coverageSegments >= requiredCoverageSegments &&
                result.targetCoverage >= DISCOVERED_MIN_TARGET_COVERAGE &&
                result.averageGroupCost <= DISCOVERED_MAX_AVERAGE_GROUP_COST &&
                result.longestTargetSkipRun <= MAX_LONGEST_TARGET_SKIP_RUN &&
                simpleRatio >= DISCOVERED_MIN_SIMPLE_GROUP_RATIO

        return result.copy(
            confident = confirmed,
            alignmentSource = if (delayOnly != null) "delay-only-validated" else "activity-correlation",
            alignmentScale = candidateScale,
            alignmentInterceptMs = candidateInterceptMs,
            activityScore = candidateActivityScore,
            activityMargin = candidateActivityMargin,
            coverageSegmentsPassed = coverageSegments,
            simpleGroupRatio = simpleRatio,
        )
    }

    private fun retimeWithSeed(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
        referenceEstimatedEndStartsMs: Set<Long>,
    ): AutoSyncTimelineRetimeResult? {
        if (reference.size < MIN_CUES || target.size < MIN_CUES) return null
        if (!coarseScale.isFinite() || coarseScale !in 0.85..1.15) return null
        if (!coarseInterceptMs.isFinite()) return null

        val centers = IntArray(target.size + 1)
        for (targetIndex in target.indices) {
            val predictedStart = transformTime(
                target[targetIndex].startTimeMs,
                coarseScale,
                coarseInterceptMs,
            )
            centers[targetIndex] = lowerBoundReference(reference, predictedStart)
        }
        val predictedEnd = transformTime(
            target.last().endTimeMs,
            coarseScale,
            coarseInterceptMs,
        )
        centers[target.size] = lowerBoundReference(reference, predictedEnd)

        val rows = Array(target.size + 1) { HashMap<Int, Cell>() }
        rows[0][0] = Cell(cost = 0.0)

        for (targetIndex in 0..target.size) {
            val row = rows[targetIndex]
            if (row.isEmpty()) continue

            val rowMax = rowMaxReferenceIndex(
                targetIndex = targetIndex,
                targetSize = target.size,
                referenceSize = reference.size,
                center = centers[targetIndex],
            )

            var referenceIndex = 0
            while (referenceIndex <= rowMax) {
                val cell = row[referenceIndex]
                if (cell != null) {
                    if (referenceIndex < reference.size && referenceIndex + 1 <= rowMax) {
                        relax(
                            rows = rows,
                            toTargetIndex = targetIndex,
                            toReferenceIndex = referenceIndex + 1,
                            candidate = Cell(
                                cost = cell.cost + SKIP_REFERENCE_COST,
                                previousReferenceIndex = referenceIndex,
                                previousTargetIndex = targetIndex,
                                referenceCount = 1,
                                targetCount = 0,
                                step = Step.SKIP_REFERENCE,
                            ),
                        )
                    }

                    if (targetIndex < target.size &&
                        isWithinBand(referenceIndex, targetIndex + 1, centers, reference.size)
                    ) {
                        relax(
                            rows = rows,
                            toTargetIndex = targetIndex + 1,
                            toReferenceIndex = referenceIndex,
                            candidate = Cell(
                                cost = cell.cost + SKIP_TARGET_COST,
                                previousReferenceIndex = referenceIndex,
                                previousTargetIndex = targetIndex,
                                referenceCount = 0,
                                targetCount = 1,
                                step = Step.SKIP_TARGET,
                            ),
                        )
                    }

                    for (shape in groupShapes) {
                        val nextReferenceIndex = referenceIndex + shape.referenceCount
                        val nextTargetIndex = targetIndex + shape.targetCount
                        if (nextReferenceIndex > reference.size || nextTargetIndex > target.size) continue
                        if (!isWithinBand(nextReferenceIndex, nextTargetIndex, centers, reference.size)) continue

                        val groupCost = groupCost(
                            reference = reference,
                            referenceIndex = referenceIndex,
                            referenceCount = shape.referenceCount,
                            target = target,
                            targetIndex = targetIndex,
                            targetCount = shape.targetCount,
                            coarseScale = coarseScale,
                            coarseInterceptMs = coarseInterceptMs,
                            referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
                        )
                        if (!groupCost.isFinite() || groupCost > MAX_GROUP_COST) continue

                        relax(
                            rows = rows,
                            toTargetIndex = nextTargetIndex,
                            toReferenceIndex = nextReferenceIndex,
                            candidate = Cell(
                                cost = cell.cost + groupCost +
                                    GROUP_COMPLEXITY_COST *
                                    (shape.referenceCount + shape.targetCount - 2),
                                previousReferenceIndex = referenceIndex,
                                previousTargetIndex = targetIndex,
                                referenceCount = shape.referenceCount,
                                targetCount = shape.targetCount,
                                step = Step.GROUP,
                                localGroupCost = groupCost,
                            ),
                        )
                    }
                }
                referenceIndex++
            }
        }

        val finalEntry = rows[target.size]
            .entries
            .minByOrNull { it.value.cost }
            ?: return null

        val steps = backtrack(
            rows = rows,
            finalReferenceIndex = finalEntry.key,
            finalTargetIndex = target.size,
        ) ?: return null

        val groups = steps
            .filter { it.step == Step.GROUP }
            .map { step ->
                AutoSyncCueGroup(
                    referenceStartIndex = step.previousReferenceIndex,
                    referenceCount = step.referenceCount,
                    targetStartIndex = step.previousTargetIndex,
                    targetCount = step.targetCount,
                    cost = step.localGroupCost,
                )
            }

        if (groups.isEmpty()) return null

        val matchedTarget = BooleanArray(target.size)
        val matchedReference = BooleanArray(reference.size)
        val retimed = target.map { cue ->
            AutoSyncRetimedCue(
                originalStartTimeMs = cue.startTimeMs,
                originalEndTimeMs = cue.endTimeMs,
                startTimeMs = transformTime(cue.startTimeMs, coarseScale, coarseInterceptMs),
                endTimeMs = transformTime(cue.endTimeMs, coarseScale, coarseInterceptMs)
                    .coerceAtLeast(transformTime(cue.startTimeMs, coarseScale, coarseInterceptMs) + 1L),
            )
        }.toMutableList()

        groups.forEach { group ->
            for (index in group.targetStartIndex until group.targetStartIndex + group.targetCount) {
                matchedTarget[index] = true
            }
            for (index in group.referenceStartIndex until group.referenceStartIndex + group.referenceCount) {
                matchedReference[index] = true
            }
            transplantGroupTiming(reference, target, group, retimed)
        }

        // Keep the output monotonic even when malformed source cues overlap backwards.
        for (index in retimed.indices) {
            val previousStart = retimed.getOrNull(index - 1)?.startTimeMs ?: Long.MIN_VALUE
            val cue = retimed[index]
            val start = max(cue.startTimeMs, previousStart)
            val end = max(cue.endTimeMs, start + 1L)
            if (start != cue.startTimeMs || end != cue.endTimeMs) {
                retimed[index] = cue.copy(startTimeMs = start, endTimeMs = end)
            }
        }

        // Local group anchoring can move neighbouring groups by slightly different amounts.
        // Preserve overlaps that already existed in the external subtitle, but never create a
        // new overlap between two cues that were sequential before AutoSync.
        for (index in 0 until retimed.lastIndex) {
            val original = target[index]
            val originalNext = target[index + 1]
            if (original.endTimeMs > originalNext.startTimeMs) continue

            val current = retimed[index]
            val next = retimed[index + 1]
            if (current.endTimeMs > next.startTimeMs && next.startTimeMs > current.startTimeMs) {
                retimed[index] = current.copy(endTimeMs = next.startTimeMs)
            }
        }

        val matchedTargetCount = matchedTarget.count { it }
        val matchedReferenceCount = matchedReference.count { it }
        val targetCoverage = matchedTargetCount.toDouble() / target.size
        val referenceCoverage = matchedReferenceCount.toDouble() / reference.size
        val skippedTarget = target.size - matchedTargetCount
        val skippedReference = reference.size - matchedReferenceCount
        val longestTargetSkipRun = longestFalseRun(matchedTarget)
        val averageGroupCost = groups.map { it.cost }.average()

        val confident =
            matchedTargetCount >= min(MIN_MATCHED_TARGET_CUES, target.size) &&
                targetCoverage >= MIN_TARGET_COVERAGE &&
                averageGroupCost <= MAX_AVERAGE_GROUP_COST &&
                longestTargetSkipRun <= MAX_LONGEST_TARGET_SKIP_RUN

        val shapeCounts = groups.groupingBy { "${it.referenceCount}:${it.targetCount}" }.eachCount()

        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = groups,
            targetCoverage = targetCoverage,
            referenceCoverage = referenceCoverage,
            skippedTargetCues = skippedTarget,
            skippedReferenceCues = skippedReference,
            longestTargetSkipRun = longestTargetSkipRun,
            averageGroupCost = averageGroupCost,
            oneToOneGroups = shapeCounts["1:1"] ?: 0,
            oneToTwoGroups = shapeCounts["1:2"] ?: 0,
            twoToOneGroups = shapeCounts["2:1"] ?: 0,
            oneToThreeGroups = shapeCounts["1:3"] ?: 0,
            threeToOneGroups = shapeCounts["3:1"] ?: 0,
            twoToTwoGroups = shapeCounts["2:2"] ?: 0,
            confident = confident,
        )
    }

    internal fun findDelayOnlyAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        allowAmbiguousMargin: Boolean = false,
    ): AutoSyncDelayOnlyAlignment? {
        if (reference.size < DELAY_ONLY_MIN_CUES || target.size < DELAY_ONLY_MIN_CUES) return null

        val referenceCoarse = buildActivityTimeline(reference, 1.0, ACTIVITY_COARSE_BIN_MS)
            ?: return null
        val targetCoarse = buildActivityTimeline(target, 1.0, ACTIVITY_COARSE_BIN_MS)
            ?: return null
        val maxOffsetBins = (ACTIVITY_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()

        var coarseBest: ActivityCandidate? = null
        val coarseCandidates = ArrayList<ActivityCandidate>(maxOffsetBins * 2 + 1)
        for (offsetBins in -maxOffsetBins..maxOffsetBins) {
            val score = scoreActivityOffset(referenceCoarse, targetCoarse, offsetBins) ?: continue
            val candidate = ActivityCandidate(
                scale = 1.0,
                interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                score = score,
            )
            coarseCandidates += candidate
            val current = coarseBest
            if (current == null || candidate.score > current.score) coarseBest = candidate
        }

        val coarse = coarseBest ?: return null
        val secondDistinct = coarseCandidates.asSequence()
            .filter { candidate ->
                abs(candidate.interceptMs - coarse.interceptMs) >= DELAY_ONLY_DISTINCT_OFFSET_MS
            }
            .maxByOrNull { it.score }
        val margin = coarse.score - (secondDistinct?.score ?: 0.0)
        if (!allowAmbiguousMargin && margin < DELAY_ONLY_MIN_MARGIN) return null

        val referenceFine = buildActivityTimeline(reference, 1.0, ACTIVITY_FINE_BIN_MS)
            ?: return null
        val targetFine = buildActivityTimeline(target, 1.0, ACTIVITY_FINE_BIN_MS)
            ?: return null

        var fineBest: ActivityCandidate? = null
        var offsetMs = coarse.interceptMs - ACTIVITY_FINE_RADIUS_MS
        while (offsetMs <= coarse.interceptMs + ACTIVITY_FINE_RADIUS_MS) {
            val offsetBins = (offsetMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
            val score = scoreActivityOffset(referenceFine, targetFine, offsetBins)
            if (score != null) {
                val candidate = ActivityCandidate(
                    scale = 1.0,
                    interceptMs = offsetBins * ACTIVITY_FINE_BIN_MS,
                    score = score,
                )
                val current = fineBest
                if (current == null || candidate.score > current.score) fineBest = candidate
            }
            offsetMs += ACTIVITY_FINE_BIN_MS
        }

        val best = fineBest ?: coarse
        if (best.score < DELAY_ONLY_MIN_SCORE) return null

        val globalOffsetBins =
            (best.interceptMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
        val localRadiusBins =
            (DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS / ACTIVITY_FINE_BIN_MS).toInt()
        val maxDeltaBins =
            (DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS / ACTIVITY_FINE_BIN_MS).toInt()

        var availableSegments = 0
        var passedSegments = 0
        for (segment in 0..2) {
            if (allowAmbiguousMargin) {
                // SDH/over-segmented references contain extra activity that can make each
                // segment prefer a slightly different local offset. For a delay-only result
                // we only care whether the ONE global delay still covers the target subtitle.
                val targetCoverage = targetActivityCoverageAtOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = globalOffsetBins,
                    segment = segment,
                ) ?: continue
                availableSegments++
                if (targetCoverage >= DELAY_ONLY_MIN_SEGMENT_SCORE) {
                    passedSegments++
                }
                continue
            }

            var segmentBestScore = Double.NEGATIVE_INFINITY
            var segmentBestOffsetBins = globalOffsetBins
            var hasScore = false

            for (delta in -localRadiusBins..localRadiusBins) {
                val candidateOffsetBins = globalOffsetBins + delta
                val score = scoreActivityOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = candidateOffsetBins,
                    segment = segment,
                ) ?: continue
                hasScore = true
                if (score > segmentBestScore) {
                    segmentBestScore = score
                    segmentBestOffsetBins = candidateOffsetBins
                }
            }

            if (!hasScore) continue
            availableSegments++
            if (
                segmentBestScore >= DELAY_ONLY_MIN_SEGMENT_SCORE &&
                abs(segmentBestOffsetBins - globalOffsetBins) <= maxDeltaBins
            ) {
                passedSegments++
            }
        }

        val requiredSegments = if (target.size < SMALL_SAMPLE_CUE_LIMIT) 2 else 3
        if (availableSegments < requiredSegments || passedSegments < requiredSegments) return null

        return AutoSyncDelayOnlyAlignment(
            offsetMs = best.interceptMs.toDouble(),
            score = best.score,
            margin = margin,
            segmentsPassed = passedSegments,
        )
    }

    internal fun buildDelayOnlyTimeline(
        target: List<SubtitleSyncCue>,
        alignment: AutoSyncDelayOnlyAlignment,
    ): AutoSyncTimelineRetimeResult {
        val offsetMs = alignment.offsetMs.roundToLong()
        val retimed = target.map { cue ->
            val start = (cue.startTimeMs + offsetMs).coerceAtLeast(0L)
            val end = (cue.endTimeMs + offsetMs).coerceAtLeast(start + 1L)
            AutoSyncRetimedCue(
                originalStartTimeMs = cue.startTimeMs,
                originalEndTimeMs = cue.endTimeMs,
                startTimeMs = start,
                endTimeMs = end,
            )
        }

        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 0.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = 0,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentSource = "delay-only",
            alignmentScale = 1.0,
            alignmentInterceptMs = alignment.offsetMs,
            activityScore = alignment.score,
            activityMargin = alignment.margin,
            coverageSegmentsPassed = alignment.segmentsPassed,
            simpleGroupRatio = 1.0,
        )
    }

    private fun targetActivityCoverageAtOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        var visibleTarget = 0
        var intersection = 0
        for (targetIndex in target.activeIndexes) {
            if (targetIndex < segmentStart) continue
            if (targetIndex > segmentEnd) break
            visibleTarget++
            val shiftedIndex = targetIndex + offsetBins
            if (shiftedIndex in reference.bins.indices && reference.bins[shiftedIndex]) {
                intersection++
            }
        }

        if (visibleTarget <= 0) return null
        return intersection.toDouble() / visibleTarget.toDouble()
    }

    private fun scoreActivityOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        var visibleTarget = 0
        var intersection = 0
        for (targetIndex in target.activeIndexes) {
            if (targetIndex < segmentStart) continue
            if (targetIndex > segmentEnd) break
            visibleTarget++
            val shiftedIndex = targetIndex + offsetBins
            if (shiftedIndex in reference.bins.indices && reference.bins[shiftedIndex]) {
                intersection++
            }
        }
        if (visibleTarget <= 0) return null

        val referenceWindowStart = max(0, segmentStart + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, segmentEnd + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        return precision * 0.72 + recall * 0.28
    }

    private fun discoverActivityAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): ActivityAlignment? {
        if (reference.size < MIN_CUES || target.size < MIN_CUES) return null

        val referenceCoarse = buildActivityTimeline(reference, 1.0, ACTIVITY_COARSE_BIN_MS)
            ?: return null
        val coarseCandidates = mutableListOf<ActivityCandidate>()
        val maxOffsetBins = (ACTIVITY_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()

        for (scale in activityScaleCandidates(reference, target)) {
            val targetActivity = buildActivityTimeline(target, scale, ACTIVITY_COARSE_BIN_MS)
                ?: continue
            for (offsetBins in -maxOffsetBins..maxOffsetBins) {
                val score = scoreActivityOffset(referenceCoarse, targetActivity, offsetBins)
                    ?: continue
                coarseCandidates += ActivityCandidate(
                    scale = scale,
                    interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                    score = score,
                )
            }
        }

        val coarseBest = coarseCandidates.maxByOrNull { it.score } ?: return null
        val targetEndMs = target.maxOf { it.endTimeMs }.toDouble()
        val secondDistinct = coarseCandidates.asSequence()
            .filter { candidate ->
                candidate !== coarseBest &&
                    isDistinctActivityTransform(coarseBest, candidate, targetEndMs)
            }
            .maxByOrNull { it.score }
        val margin = coarseBest.score - (secondDistinct?.score ?: 0.0)

        val referenceFine = buildActivityTimeline(reference, 1.0, ACTIVITY_FINE_BIN_MS)
            ?: return null
        val targetFine = buildActivityTimeline(target, coarseBest.scale, ACTIVITY_FINE_BIN_MS)
            ?: return null

        var fineBest: ActivityCandidate? = null
        var offsetMs = coarseBest.interceptMs - ACTIVITY_FINE_RADIUS_MS
        while (offsetMs <= coarseBest.interceptMs + ACTIVITY_FINE_RADIUS_MS) {
            val offsetBins = (offsetMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
            val score = scoreActivityOffset(referenceFine, targetFine, offsetBins)
            if (score != null) {
                val candidate = ActivityCandidate(
                    scale = coarseBest.scale,
                    interceptMs = offsetBins * ACTIVITY_FINE_BIN_MS,
                    score = score,
                )
                val current = fineBest
                if (current == null || candidate.score > current.score) fineBest = candidate
            }
            offsetMs += ACTIVITY_FINE_BIN_MS
        }

        val best = fineBest ?: coarseBest
        return ActivityAlignment(
            scale = best.scale,
            interceptMs = best.interceptMs.toDouble(),
            score = best.score,
            margin = margin,
        )
    }

    private fun buildActivityTimeline(
        cues: List<SubtitleSyncCue>,
        scale: Double,
        binMs: Long,
    ): ActivityTimeline? {
        if (!scale.isFinite() || scale !in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) return null
        if (cues.isEmpty() || binMs <= 0L) return null

        val scaled = ArrayList<Pair<Long, Long>>(cues.size)
        var maxEndMs = 0L
        for (cue in cues) {
            val safeStart = cue.startTimeMs.coerceAtLeast(0L)
            val safeEnd = cue.endTimeMs
                .coerceAtLeast(safeStart + 1L)
                .coerceAtMost(safeStart + ACTIVITY_MAX_CUE_DURATION_MS)
            val start = (safeStart * scale).roundToLong().coerceAtLeast(0L)
            val end = (safeEnd * scale).roundToLong().coerceAtLeast(start + 1L)
            if (end > ACTIVITY_MAX_TIMELINE_MS) return null
            scaled += start to end
            maxEndMs = max(maxEndMs, end)
        }

        val binCount = max(1, ceil((maxEndMs + 1L).toDouble() / binMs.toDouble()).toInt())
        val bins = BooleanArray(binCount)
        for ((start, end) in scaled) {
            val first = (start / binMs).toInt().coerceIn(0, binCount - 1)
            val lastExclusive = ceil(end.toDouble() / binMs.toDouble())
                .toInt()
                .coerceIn(first + 1, binCount)
            for (index in first until lastExclusive) bins[index] = true
        }

        var activeCount = 0
        for (active in bins) if (active) activeCount++
        if (activeCount == 0) return null

        val activeIndexes = IntArray(activeCount)
        val prefix = IntArray(binCount + 1)
        var cursor = 0
        for (index in bins.indices) {
            if (bins[index]) {
                activeIndexes[cursor++] = index
                prefix[index + 1] = prefix[index] + 1
            } else {
                prefix[index + 1] = prefix[index]
            }
        }
        return ActivityTimeline(
            bins = bins,
            activeIndexes = activeIndexes,
            prefix = prefix,
            firstActive = activeIndexes.first(),
            lastActive = activeIndexes.last(),
        )
    }

    private fun scoreActivityOffset(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
    ): Double? {
        val sourceStart = max(0, -offsetBins)
        val sourceEnd = min(target.bins.lastIndex, reference.bins.lastIndex - offsetBins)
        if (sourceEnd < sourceStart) return null
        val visibleTarget = target.prefix[sourceEnd + 1] - target.prefix[sourceStart]
        if (visibleTarget <= 0) return null

        var intersection = 0
        for (targetIndex in target.activeIndexes) {
            val shiftedIndex = targetIndex + offsetBins
            if (shiftedIndex in reference.bins.indices && reference.bins[shiftedIndex]) {
                intersection++
            }
        }

        val referenceWindowStart = max(0, target.firstActive + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, target.lastActive + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        val visibility = visibleTarget.toDouble() / target.activeIndexes.size.toDouble()
        return (precision * 0.72 + recall * 0.28) *
            (0.85 + 0.15 * visibility.coerceIn(0.0, 1.0))
    }

    private fun activityScaleCandidates(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): List<Double> {
        val candidates = mutableListOf(
            1.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
            24.0 / 23.976,
            23.976 / 24.0,
        )
        val referenceSpan = reference.last().startTimeMs - reference.first().startTimeMs
        val targetSpan = target.last().startTimeMs - target.first().startTimeMs
        if (referenceSpan > 0L && targetSpan > 0L) {
            val spanRatio = referenceSpan.toDouble() / targetSpan.toDouble()
            if (spanRatio in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) candidates += spanRatio
        }

        val unique = mutableListOf<Double>()
        candidates
            .filter { it.isFinite() && it in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE }
            .sorted()
            .forEach { candidate ->
                if (unique.none { abs(it - candidate) < ACTIVITY_SCALE_DEDUP }) unique += candidate
            }
        return unique
    }

    private fun isDistinctActivityTransform(
        first: ActivityCandidate,
        second: ActivityCandidate,
        targetEndMs: Double,
    ): Boolean {
        val startDifference = abs(first.interceptMs - second.interceptMs).toDouble()
        val endDifference = abs(
            targetEndMs * first.scale + first.interceptMs -
                (targetEndMs * second.scale + second.interceptMs),
        )
        return max(startDifference, endDifference) >= ACTIVITY_DISTINCT_TRANSFORM_MS
    }

    private fun coverageSegmentsPassed(
        result: AutoSyncTimelineRetimeResult,
        targetSize: Int,
    ): Int {
        if (targetSize < MIN_CUES || result.groups.isEmpty()) return 0
        val matched = BooleanArray(targetSize)
        val costs = Array(3) { mutableListOf<Double>() }
        result.groups.forEach { group ->
            val groupEnd = (group.targetStartIndex + group.targetCount).coerceAtMost(targetSize)
            for (index in group.targetStartIndex until groupEnd) matched[index] = true
            val midpoint = group.targetStartIndex + (group.targetCount - 1) / 2
            val segment =
                ((midpoint.toLong() * 3L) / targetSize.coerceAtLeast(1)).toInt().coerceIn(0, 2)
            costs[segment] += group.cost
        }

        var passed = 0
        for (segment in 0..2) {
            val start = segment * targetSize / 3
            val end = if (segment == 2) targetSize else (segment + 1) * targetSize / 3
            val length = (end - start).coerceAtLeast(1)
            var matchedCount = 0
            for (index in start until end) if (matched[index]) matchedCount++
            val coverage = matchedCount.toDouble() / length
            val segmentCosts = costs[segment]
            val averageCost =
                if (segmentCosts.isEmpty()) Double.POSITIVE_INFINITY else segmentCosts.average()
            val requiredGroups = min(COVERAGE_SEGMENT_MIN_GROUPS, length)
            if (
                coverage >= COVERAGE_SEGMENT_MIN_COVERAGE &&
                averageCost <= COVERAGE_SEGMENT_MAX_AVERAGE_COST &&
                segmentCosts.size >= requiredGroups
            ) {
                passed++
            }
        }
        return passed
    }

    private fun structuralGroupRatio(
        result: AutoSyncTimelineRetimeResult,
        referenceSize: Int,
        targetSize: Int,
    ): Double {
        if (result.groups.isEmpty()) return 0.0

        var compatible =
            result.oneToOneGroups +
                result.oneToTwoGroups +
                result.twoToOneGroups +
                result.twoToTwoGroups

        val enoughTimelineCoverage =
            result.targetCoverage >= SEGMENTATION_MIN_TIMELINE_COVERAGE &&
                result.referenceCoverage >= SEGMENTATION_MIN_TIMELINE_COVERAGE

        if (enoughTimelineCoverage) {
            val referencePerTarget =
                referenceSize.toDouble() / targetSize.coerceAtLeast(1).toDouble()
            val targetPerReference =
                targetSize.toDouble() / referenceSize.coerceAtLeast(1).toDouble()

            if (referencePerTarget >= SEGMENTATION_IMBALANCE_RATIO) {
                compatible += result.threeToOneGroups
            }
            if (targetPerReference >= SEGMENTATION_IMBALANCE_RATIO) {
                compatible += result.oneToThreeGroups
            }
        }

        return compatible.toDouble() / result.groups.size
    }

    private data class ExternalCueKey(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String,
    )

    private data class ActivityTimeline(
        val bins: BooleanArray,
        val activeIndexes: IntArray,
        val prefix: IntArray,
        val firstActive: Int,
        val lastActive: Int,
    )

    private data class ActivityCandidate(
        val scale: Double,
        val interceptMs: Long,
        val score: Double,
    )

    private data class ActivityAlignment(
        val scale: Double,
        val interceptMs: Double,
        val score: Double,
        val margin: Double,
    )

    private fun transplantGroupTiming(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        group: AutoSyncCueGroup,
        output: MutableList<AutoSyncRetimedCue>,
    ) {
        val targetStartIndex = group.targetStartIndex
        val targetEndIndex = group.targetStartIndex + group.targetCount - 1
        val referenceStart = reference[group.referenceStartIndex].startTimeMs

        // The affine pass already preserves each external cue's duration (including FPS scaling).
        // Move the matched group as one unit so its first cue starts with the embedded reference,
        // but never inherit a foreign-language or estimated reference end time.
        val groupShiftMs = referenceStart - output[targetStartIndex].startTimeMs

        for (index in targetStartIndex..targetEndIndex) {
            val cue = output[index]
            val durationMs = (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(1L)
            val newStart = (cue.startTimeMs + groupShiftMs).coerceAtLeast(0L)
            output[index] = cue.copy(
                startTimeMs = newStart,
                endTimeMs = newStart + durationMs,
            )
        }
    }

    private fun groupCost(
        reference: List<SubtitleSyncCue>,
        referenceIndex: Int,
        referenceCount: Int,
        target: List<SubtitleSyncCue>,
        targetIndex: Int,
        targetCount: Int,
        coarseScale: Double,
        coarseInterceptMs: Double,
        referenceEstimatedEndStartsMs: Set<Long>,
    ): Double {
        val referenceStart = reference[referenceIndex].startTimeMs.toDouble()
        val referenceEnd = reference[referenceIndex + referenceCount - 1].endTimeMs.toDouble()
        val targetStart = transformTimeDouble(
            target[targetIndex].startTimeMs,
            coarseScale,
            coarseInterceptMs,
        )
        val targetEnd = transformTimeDouble(
            target[targetIndex + targetCount - 1].endTimeMs,
            coarseScale,
            coarseInterceptMs,
        )

        val referenceDuration = max(1.0, referenceEnd - referenceStart)
        val targetDuration = max(1.0, targetEnd - targetStart)
        val referenceMid = (referenceStart + referenceEnd) * 0.5
        val targetMid = (targetStart + targetEnd) * 0.5

        val startError = abs(referenceStart - targetStart) / START_END_TOLERANCE_MS
        val endError = abs(referenceEnd - targetEnd) / START_END_TOLERANCE_MS
        val midpointError = abs(referenceMid - targetMid) / MIDPOINT_TOLERANCE_MS
        val durationError = abs(referenceDuration - targetDuration) / DURATION_TOLERANCE_MS
        val referenceEndEstimated =
            referenceEstimatedEndStartsMs.isNotEmpty() &&
                (referenceIndex until referenceIndex + referenceCount).any { index ->
                    reference[index].startTimeMs in referenceEstimatedEndStartsMs
                }

        return if (referenceEndEstimated) {
            // MKV CueTime is authoritative even when CueDuration is absent. Keep the inferred
            // end useful, but do not let it outweigh the real embedded start timestamp.
            startError * 0.58 +
                endError * 0.16 +
                midpointError * 0.16 +
                durationError * 0.10
        } else {
            startError * 0.34 +
                endError * 0.34 +
                midpointError * 0.18 +
                durationError * 0.14
        }
    }

    private fun backtrack(
        rows: Array<HashMap<Int, Cell>>,
        finalReferenceIndex: Int,
        finalTargetIndex: Int,
    ): List<BacktrackStep>? {
        val reversed = ArrayList<BacktrackStep>()
        var referenceIndex = finalReferenceIndex
        var targetIndex = finalTargetIndex
        var guard = 0
        val guardLimit = rows.size * 8 + finalReferenceIndex * 2 + 32

        while (referenceIndex != 0 || targetIndex != 0) {
            if (++guard > guardLimit) return null
            val cell = rows.getOrNull(targetIndex)?.get(referenceIndex) ?: return null
            if (cell.step == Step.START) return null
            reversed += BacktrackStep(
                step = cell.step,
                previousReferenceIndex = cell.previousReferenceIndex,
                previousTargetIndex = cell.previousTargetIndex,
                referenceCount = cell.referenceCount,
                targetCount = cell.targetCount,
                localGroupCost = cell.localGroupCost,
            )
            referenceIndex = cell.previousReferenceIndex
            targetIndex = cell.previousTargetIndex
        }

        reversed.reverse()
        return reversed
    }

    private fun relax(
        rows: Array<HashMap<Int, Cell>>,
        toTargetIndex: Int,
        toReferenceIndex: Int,
        candidate: Cell,
    ) {
        val current = rows[toTargetIndex][toReferenceIndex]
        if (current == null || candidate.cost + 1e-9 < current.cost) {
            rows[toTargetIndex][toReferenceIndex] = candidate
        }
    }

    private fun rowMaxReferenceIndex(
        targetIndex: Int,
        targetSize: Int,
        referenceSize: Int,
        center: Int,
    ): Int {
        if (targetIndex == 0) {
            return min(referenceSize, center + BAND_RADIUS_CUES)
        }
        if (targetIndex == targetSize) {
            return min(referenceSize, center + BAND_RADIUS_CUES)
        }
        return min(referenceSize, center + BAND_RADIUS_CUES)
    }

    private fun isWithinBand(
        referenceIndex: Int,
        targetIndex: Int,
        centers: IntArray,
        referenceSize: Int,
    ): Boolean {
        if (referenceIndex !in 0..referenceSize) return false
        val center = centers[targetIndex.coerceIn(0, centers.lastIndex)]
        return abs(referenceIndex - center) <= BAND_RADIUS_CUES || targetIndex == 0
    }

    private fun lowerBoundReference(reference: List<SubtitleSyncCue>, timeMs: Long): Int {
        var low = 0
        var high = reference.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (reference[mid].startTimeMs < timeMs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low.coerceIn(0, reference.size)
    }

    private fun transformTime(timeMs: Long, scale: Double, interceptMs: Double): Long =
        transformTimeDouble(timeMs, scale, interceptMs).roundToLong().coerceAtLeast(0L)

    private fun transformTimeDouble(timeMs: Long, scale: Double, interceptMs: Double): Double =
        timeMs.toDouble() * scale + interceptMs

    private fun longestFalseRun(values: BooleanArray): Int {
        var longest = 0
        var current = 0
        for (value in values) {
            if (value) {
                current = 0
            } else {
                current++
                longest = max(longest, current)
            }
        }
        return longest
    }

    private data class GroupShape(
        val referenceCount: Int,
        val targetCount: Int,
    )

    private enum class Step {
        START,
        GROUP,
        SKIP_REFERENCE,
        SKIP_TARGET,
    }

    private data class Cell(
        val cost: Double,
        val previousReferenceIndex: Int = -1,
        val previousTargetIndex: Int = -1,
        val referenceCount: Int = 0,
        val targetCount: Int = 0,
        val step: Step = Step.START,
        val localGroupCost: Double = 0.0,
    )

    private data class BacktrackStep(
        val step: Step,
        val previousReferenceIndex: Int,
        val previousTargetIndex: Int,
        val referenceCount: Int,
        val targetCount: Int,
        val localGroupCost: Double,
    )
}

internal data class AutoSyncCueGroup(
    val referenceStartIndex: Int,
    val referenceCount: Int,
    val targetStartIndex: Int,
    val targetCount: Int,
    val cost: Double,
)

internal data class AutoSyncRetimedCue(
    val originalStartTimeMs: Long,
    val originalEndTimeMs: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

internal data class AutoSyncDelayOnlyAlignment(
    val offsetMs: Double,
    val score: Double,
    val margin: Double,
    val segmentsPassed: Int,
)

internal data class AutoSyncTimelineRetimeResult(
    val cues: List<AutoSyncRetimedCue>,
    val groups: List<AutoSyncCueGroup>,
    val targetCoverage: Double,
    val referenceCoverage: Double,
    val skippedTargetCues: Int,
    val skippedReferenceCues: Int,
    val longestTargetSkipRun: Int,
    val averageGroupCost: Double,
    val oneToOneGroups: Int,
    val oneToTwoGroups: Int,
    val twoToOneGroups: Int,
    val oneToThreeGroups: Int,
    val threeToOneGroups: Int,
    val twoToTwoGroups: Int,
    val confident: Boolean,
    val alignmentSource: String = "provided",
    val alignmentScale: Double = 1.0,
    val alignmentInterceptMs: Double = 0.0,
    val activityScore: Double = 0.0,
    val activityMargin: Double = 0.0,
    val coverageSegmentsPassed: Int = 0,
    val simpleGroupRatio: Double = 0.0,
)
