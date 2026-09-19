package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Lightweight, text-independent full-timeline subtitle retiming.
 *
 * The existing AutoSync matcher supplies a coarse affine transform that puts an add-on subtitle
 * near an embedded subtitle timeline. This aligner then walks both ordered cue sequences and
 * resolves local 1:1 / 1:2 / 2:1 / 1:3 / 3:1 / 2:2 groupings plus skips. Matched add-on groups
 * inherit the embedded timing envelope; unmatched add-on cues retain the coarse affine timing.
 *
 * This file is deliberately commonMain and player-independent so the algorithm can be unit-tested
 * without Android, Media3, networking, or playback state.
 */
internal object AutoSyncTimelineRetimer {
    private const val MIN_CUES = 8
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

    // Rejected/ambiguous V1 seeds must earn independent full-film evidence before V2 can apply.
    private const val INDEPENDENT_SEED_SAMPLES = 42
    private const val INDEPENDENT_SEED_SEARCH_WINDOW_MS = 180_000L
    private const val INDEPENDENT_PATTERN_RADIUS_CUES = 5
    private const val INDEPENDENT_PATTERN_DISTANCE_TOLERANCE_MS = 900.0
    private const val INDEPENDENT_PATTERN_MAX_COST = 1.20
    private const val INDEPENDENT_ANCHOR_CANDIDATES_PER_SAMPLE = 4
    private const val INDEPENDENT_CHAIN_INTERCEPT_TOLERANCE_MS = 8_000.0
    private const val INDEPENDENT_CHAIN_MIN_ANCHORS = 10
    private const val INDEPENDENT_CHAIN_MIN_SEGMENTS = 3
    private const val INDEPENDENT_CHAIN_MAX_AVERAGE_COST = 1.00
    private const val INDEPENDENT_CHAIN_EDGE_FRACTION = 0.12
    private const val INDEPENDENT_CHAIN_MIN_SPAN_RATIO = 0.76
    private const val MAX_INDEPENDENT_SEEDS = 3
    private const val SEED_DEDUP_BUCKET_MS = 250.0
    private const val AMBIGUOUS_MIN_TARGET_COVERAGE = 0.90
    private const val AMBIGUOUS_MAX_AVERAGE_GROUP_COST = 1.10
    private const val AMBIGUOUS_MAX_TARGET_SKIP_RUN = 8
    private const val AMBIGUOUS_MIN_SIMPLE_GROUP_RATIO = 0.55
    private const val ANCHOR_SEGMENT_MIN_COVERAGE = 0.72
    private const val ANCHOR_SEGMENT_MAX_AVERAGE_COST = 1.35
    private const val ANCHOR_SEGMENT_MIN_GROUPS = 4

    private val groupShapes = arrayOf(
        GroupShape(referenceCount = 1, targetCount = 1),
        GroupShape(referenceCount = 1, targetCount = 2),
        GroupShape(referenceCount = 2, targetCount = 1),
        GroupShape(referenceCount = 1, targetCount = 3),
        GroupShape(referenceCount = 3, targetCount = 1),
        GroupShape(referenceCount = 2, targetCount = 2),
    )

    fun retime(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
        requireIndependentAnchors: Boolean = false,
    ): AutoSyncTimelineRetimeResult? {
        if (!requireIndependentAnchors) {
            val result = retimeWithSeed(reference, target, coarseScale, coarseInterceptMs)
                ?: return null
            return result.copy(
                seedSource = "coarse",
                seedInterceptMs = coarseInterceptMs,
                anchorSegmentsPassed = anchorSegmentsPassed(result, target.size),
                simpleGroupRatio = simpleGroupRatio(result),
            )
        }

        val seeds = independentSeedCandidates(
            reference = reference,
            target = target,
            scale = coarseScale,
        )
        if (seeds.isEmpty()) return null

        val evaluated = seeds.mapNotNull { seed ->
            retimeWithSeed(reference, target, coarseScale, seed.interceptMs)?.let { result ->
                val coverageSegments = anchorSegmentsPassed(result, target.size)
                val simpleRatio = simpleGroupRatio(result)
                val independentlyConfirmed =
                    result.confident &&
                        seed.anchorSegments >= INDEPENDENT_CHAIN_MIN_SEGMENTS &&
                        seed.anchorSpanRatio >= INDEPENDENT_CHAIN_MIN_SPAN_RATIO &&
                        coverageSegments == 3 &&
                        result.targetCoverage >= AMBIGUOUS_MIN_TARGET_COVERAGE &&
                        result.averageGroupCost <= AMBIGUOUS_MAX_AVERAGE_GROUP_COST &&
                        result.longestTargetSkipRun <= AMBIGUOUS_MAX_TARGET_SKIP_RUN &&
                        simpleRatio >= AMBIGUOUS_MIN_SIMPLE_GROUP_RATIO

                SeedEvaluation(
                    seed = seed,
                    result = result,
                    coverageSegments = coverageSegments,
                    simpleGroupRatio = simpleRatio,
                    independentlyConfirmed = independentlyConfirmed,
                )
            }
        }

        // Prefer an independently-derived seed that actually passes the final safety gate.
        // Otherwise a slightly higher raw DP score can suppress a safe seed and force a reject.
        val best = evaluated.maxWithOrNull(
            compareBy<SeedEvaluation> {
                if (it.independentlyConfirmed) 1 else 0
            }
                .thenBy { timelineQualityScore(it.result) }
                .thenBy { it.seed.anchorSpanRatio }
                .thenBy { it.seed.anchorCount }
                .thenBy { -it.seed.anchorAverageCost }
                .thenBy { -it.seed.anchorInterceptMadMs },
        ) ?: return null

        return best.result.copy(
            confident = best.independentlyConfirmed,
            seedSource = "independent-chain",
            seedInterceptMs = best.seed.interceptMs,
            anchorSegmentsPassed = best.coverageSegments,
            simpleGroupRatio = best.simpleGroupRatio,
            seedAnchorCount = best.seed.anchorCount,
            seedAnchorSegments = best.seed.anchorSegments,
            seedAnchorSpanRatio = best.seed.anchorSpanRatio,
            seedCandidatesEvaluated = evaluated.size,
        )
    }

    private fun retimeWithSeed(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
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

    private fun independentSeedCandidates(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        scale: Double,
    ): List<SeedCandidate> {
        if (reference.size < MIN_CUES || target.size < MIN_CUES) return emptyList()
        val targetSpan = target.last().startTimeMs - target.first().startTimeMs
        val referenceSpan = reference.last().startTimeMs - reference.first().startTimeMs
        if (targetSpan <= 0L || referenceSpan <= 0L) return emptyList()

        val sampledTargetIndexes = evenlySampleTargetIndexes(
            cueCount = target.size,
            maxSamples = INDEPENDENT_SEED_SAMPLES,
        )
        val minimumAnchorCount = min(
            INDEPENDENT_CHAIN_MIN_ANCHORS,
            max(6, (sampledTargetIndexes.size + 3) / 4),
        )

        // Keep only the few best local candidates per sample. Weak repetitive cadence matches
        // no longer accumulate global votes simply because there are many of them.
        val anchors = mutableListOf<SpacingAnchor>()
        for (targetIndex in sampledTargetIndexes) {
            val targetCue = target[targetIndex]
            val progress =
                (targetCue.startTimeMs - target.first().startTimeMs).toDouble() /
                    targetSpan.toDouble()
            val expectedReferenceTime =
                (reference.first().startTimeMs + progress * referenceSpan).roundToLong()
            val from = lowerBoundReference(
                reference,
                expectedReferenceTime - INDEPENDENT_SEED_SEARCH_WINDOW_MS,
            )
            val to = lowerBoundReference(
                reference,
                expectedReferenceTime + INDEPENDENT_SEED_SEARCH_WINDOW_MS,
            )

            val localCandidates = mutableListOf<SpacingAnchor>()
            for (referenceIndex in from until to) {
                val patternCost = spacingPatternCost(
                    reference = reference,
                    referenceIndex = referenceIndex,
                    target = target,
                    targetIndex = targetIndex,
                    scale = scale,
                )
                if (!patternCost.isFinite() || patternCost > INDEPENDENT_PATTERN_MAX_COST) {
                    continue
                }

                localCandidates += SpacingAnchor(
                    targetIndex = targetIndex,
                    referenceIndex = referenceIndex,
                    interceptMs =
                        reference[referenceIndex].startTimeMs.toDouble() -
                            targetCue.startTimeMs.toDouble() * scale,
                    patternCost = patternCost,
                )
            }

            localCandidates
                .sortedWith(
                    compareBy<SpacingAnchor> { it.patternCost }
                        .thenBy { it.referenceIndex },
                )
                .take(INDEPENDENT_ANCHOR_CANDIDATES_PER_SAMPLE)
                .forEach(anchors::add)
        }

        if (anchors.isEmpty()) return emptyList()

        // Windows are centered on real anchor candidates instead of fixed offset buckets, so a
        // correct chain cannot be split just because its offset lands on a bucket boundary.
        return anchors
            .asSequence()
            .map { center ->
                anchors.filter { anchor ->
                    abs(anchor.interceptMs - center.interceptMs) <=
                        INDEPENDENT_CHAIN_INTERCEPT_TOLERANCE_MS
                }
            }
            .mapNotNull { nearby ->
                buildIndependentSeedFromAnchors(
                    anchors = nearby,
                    targetSize = target.size,
                    minimumAnchorCount = minimumAnchorCount,
                )
            }
            .sortedWith(
                compareByDescending<SeedCandidate> { it.anchorSpanRatio }
                    .thenByDescending { it.anchorCount }
                    .thenBy { it.anchorAverageCost }
                    .thenBy { it.anchorInterceptMadMs }
                    .thenBy { abs(it.interceptMs) },
            )
            .distinctBy { seed ->
                (seed.interceptMs / SEED_DEDUP_BUCKET_MS).roundToLong()
            }
            .take(MAX_INDEPENDENT_SEEDS)
            .toList()
    }

    private fun buildIndependentSeedFromAnchors(
        anchors: List<SpacingAnchor>,
        targetSize: Int,
        minimumAnchorCount: Int,
    ): SeedCandidate? {
        if (anchors.isEmpty() || targetSize <= 1) return null

        val ordered = anchors
            .groupBy { it.targetIndex }
            .values
            .mapNotNull { candidates ->
                candidates.minWithOrNull(
                    compareBy<SpacingAnchor> { it.patternCost }
                        .thenBy { it.referenceIndex },
                )
            }
            .sortedBy { it.targetIndex }

        val chain = longestMonotonicAnchorChain(ordered)
        if (chain.size < minimumAnchorCount) return null

        var segmentMask = 0
        chain.forEach { anchor ->
            val segment =
                ((anchor.targetIndex.toLong() * 3L) / targetSize)
                    .toInt()
                    .coerceIn(0, 2)
            segmentMask = segmentMask or (1 shl segment)
        }
        val anchorSegments = spacingSegmentCount(segmentMask)
        if (anchorSegments < INDEPENDENT_CHAIN_MIN_SEGMENTS) return null

        val denominator = (targetSize - 1).coerceAtLeast(1).toDouble()
        val firstFraction = chain.first().targetIndex / denominator
        val lastFraction = chain.last().targetIndex / denominator
        val spanRatio = (lastFraction - firstFraction).coerceIn(0.0, 1.0)

        if (firstFraction > INDEPENDENT_CHAIN_EDGE_FRACTION) return null
        if (lastFraction < 1.0 - INDEPENDENT_CHAIN_EDGE_FRACTION) return null
        if (spanRatio < INDEPENDENT_CHAIN_MIN_SPAN_RATIO) return null

        val averageCost = chain.map { it.patternCost }.average()
        if (averageCost > INDEPENDENT_CHAIN_MAX_AVERAGE_COST) return null

        val intercepts = chain.map { it.interceptMs }
        val intercept = medianDouble(intercepts)
        val interceptMad = medianDouble(
            intercepts.map { value -> abs(value - intercept) },
        )

        return SeedCandidate(
            interceptMs = intercept,
            anchorCount = chain.size,
            anchorSegments = anchorSegments,
            anchorSpanRatio = spanRatio,
            anchorAverageCost = averageCost,
            anchorInterceptMadMs = interceptMad,
        )
    }

    private fun longestMonotonicAnchorChain(
        anchors: List<SpacingAnchor>,
    ): List<SpacingAnchor> {
        if (anchors.isEmpty()) return emptyList()

        val lengths = IntArray(anchors.size) { 1 }
        val costs = DoubleArray(anchors.size) { index -> anchors[index].patternCost }
        val previous = IntArray(anchors.size) { -1 }

        for (index in anchors.indices) {
            for (earlier in 0 until index) {
                if (
                    anchors[earlier].targetIndex >= anchors[index].targetIndex ||
                    anchors[earlier].referenceIndex >= anchors[index].referenceIndex
                ) {
                    continue
                }

                val candidateLength = lengths[earlier] + 1
                val candidateCost = costs[earlier] + anchors[index].patternCost
                if (
                    candidateLength > lengths[index] ||
                    (candidateLength == lengths[index] && candidateCost < costs[index])
                ) {
                    lengths[index] = candidateLength
                    costs[index] = candidateCost
                    previous[index] = earlier
                }
            }
        }

        var bestEnd = 0
        for (index in 1 until anchors.size) {
            if (
                lengths[index] > lengths[bestEnd] ||
                (lengths[index] == lengths[bestEnd] && costs[index] < costs[bestEnd])
            ) {
                bestEnd = index
            }
        }

        val reversed = mutableListOf<SpacingAnchor>()
        var cursor = bestEnd
        while (cursor >= 0) {
            reversed += anchors[cursor]
            cursor = previous[cursor]
        }
        reversed.reverse()
        return reversed
    }

    private fun spacingPatternCost(
        reference: List<SubtitleSyncCue>,
        referenceIndex: Int,
        target: List<SubtitleSyncCue>,
        targetIndex: Int,
        scale: Double,
    ): Double {
        val referencePattern = localSpacingPattern(reference, referenceIndex, 1.0)
        val targetPattern = localSpacingPattern(target, targetIndex, scale)
        if (referencePattern.size < 4 || targetPattern.size < 4) {
            return Double.POSITIVE_INFINITY
        }

        val targetToReference = directedSpacingPatternCost(targetPattern, referencePattern)
        val referenceToTarget = directedSpacingPatternCost(referencePattern, targetPattern)
        return (targetToReference + referenceToTarget) * 0.5
    }

    private fun localSpacingPattern(
        cues: List<SubtitleSyncCue>,
        index: Int,
        scale: Double,
    ): List<Double> {
        val anchor = cues[index].startTimeMs
        return buildList {
            for (distance in 1..INDEPENDENT_PATTERN_RADIUS_CUES) {
                val previous = index - distance
                if (previous >= 0) {
                    add(-(anchor - cues[previous].startTimeMs).toDouble() * scale)
                }
                val next = index + distance
                if (next < cues.size) {
                    add((cues[next].startTimeMs - anchor).toDouble() * scale)
                }
            }
        }
    }

    private fun directedSpacingPatternCost(
        source: List<Double>,
        target: List<Double>,
    ): Double {
        val residuals = source.map { sourceDistance ->
            target.minOf { targetDistance -> abs(targetDistance - sourceDistance) }
        }.sorted()
        if (residuals.isEmpty()) return Double.POSITIVE_INFINITY

        // Keep the best 70% so a split/merged cue does not destroy an otherwise distinctive
        // local timing shape. The opposite direction still penalizes excessive extra cues.
        val keep = max(4, (residuals.size * 7 + 9) / 10).coerceAtMost(residuals.size)
        return residuals.take(keep).average() / INDEPENDENT_PATTERN_DISTANCE_TOLERANCE_MS
    }

    private fun evenlySampleTargetIndexes(
        cueCount: Int,
        maxSamples: Int,
    ): List<Int> {
        if (cueCount <= 0) return emptyList()
        if (cueCount <= maxSamples) return (0 until cueCount).toList()
        if (maxSamples <= 1) return listOf(cueCount / 2)
        val lastIndex = cueCount - 1
        return (0 until maxSamples)
            .map { sampleIndex ->
                (sampleIndex.toLong() * lastIndex / (maxSamples - 1)).toInt()
            }
            .distinct()
    }

    private fun spacingSegmentCount(mask: Int): Int =
        (0..2).count { segment -> (mask and (1 shl segment)) != 0 }

    private fun anchorSegmentsPassed(
        result: AutoSyncTimelineRetimeResult,
        targetSize: Int,
    ): Int {
        if (targetSize < 3 || result.groups.isEmpty()) return 0
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
            if (
                coverage >= ANCHOR_SEGMENT_MIN_COVERAGE &&
                averageCost <= ANCHOR_SEGMENT_MAX_AVERAGE_COST &&
                segmentCosts.size >= ANCHOR_SEGMENT_MIN_GROUPS
            ) {
                passed++
            }
        }
        return passed
    }

    private fun simpleGroupRatio(result: AutoSyncTimelineRetimeResult): Double {
        if (result.groups.isEmpty()) return 0.0
        val simple =
            result.oneToOneGroups +
                result.oneToTwoGroups +
                result.twoToOneGroups +
                result.twoToTwoGroups
        return simple.toDouble() / result.groups.size
    }

    private fun timelineQualityScore(result: AutoSyncTimelineRetimeResult): Double {
        val simple = simpleGroupRatio(result)
        val costScore = 1.0 / (1.0 + result.averageGroupCost.coerceAtLeast(0.0))
        val skipPenalty = min(result.longestTargetSkipRun, 20) * 0.004
        return result.targetCoverage * 0.42 +
            result.referenceCoverage * 0.15 +
            costScore * 0.23 +
            simple * 0.20 -
            skipPenalty
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private data class SpacingAnchor(
        val targetIndex: Int,
        val referenceIndex: Int,
        val interceptMs: Double,
        val patternCost: Double,
    )

    private data class SeedCandidate(
        val interceptMs: Double,
        val anchorCount: Int,
        val anchorSegments: Int,
        val anchorSpanRatio: Double,
        val anchorAverageCost: Double,
        val anchorInterceptMadMs: Double,
    )

    private data class SeedEvaluation(
        val seed: SeedCandidate,
        val result: AutoSyncTimelineRetimeResult,
        val coverageSegments: Int,
        val simpleGroupRatio: Double,
        val independentlyConfirmed: Boolean,
    )

    private fun transplantGroupTiming(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        group: AutoSyncCueGroup,
        output: MutableList<AutoSyncRetimedCue>,
    ) {
        val referenceStart = reference[group.referenceStartIndex].startTimeMs
        val referenceEnd = reference[group.referenceStartIndex + group.referenceCount - 1].endTimeMs
            .coerceAtLeast(referenceStart + 1L)

        val targetStartIndex = group.targetStartIndex
        val targetEndIndex = group.targetStartIndex + group.targetCount - 1
        val targetEnvelopeStart = target[targetStartIndex].startTimeMs
        val targetEnvelopeEnd = target[targetEndIndex].endTimeMs.coerceAtLeast(targetEnvelopeStart + 1L)
        val targetEnvelopeDuration = (targetEnvelopeEnd - targetEnvelopeStart).toDouble()
        val referenceDuration = (referenceEnd - referenceStart).toDouble()

        for (index in targetStartIndex..targetEndIndex) {
            val cue = target[index]
            val relativeStart = ((cue.startTimeMs - targetEnvelopeStart) / targetEnvelopeDuration)
                .coerceIn(0.0, 1.0)
            val relativeEnd = ((cue.endTimeMs - targetEnvelopeStart) / targetEnvelopeDuration)
                .coerceIn(relativeStart, 1.0)

            val newStart = (referenceStart + relativeStart * referenceDuration).roundToLong()
            val newEnd = (referenceStart + relativeEnd * referenceDuration)
                .roundToLong()
                .coerceAtLeast(newStart + 1L)

            output[index] = output[index].copy(
                startTimeMs = newStart,
                endTimeMs = newEnd,
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

        return startError * 0.34 +
            endError * 0.34 +
            midpointError * 0.18 +
            durationError * 0.14
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
    val seedSource: String = "coarse",
    val seedInterceptMs: Double = 0.0,
    val anchorSegmentsPassed: Int = 0,
    val simpleGroupRatio: Double = 0.0,
    val seedAnchorCount: Int = 0,
    val seedAnchorSegments: Int = 0,
    val seedAnchorSpanRatio: Double = 0.0,
    val seedCandidatesEvaluated: Int = 0,
)
