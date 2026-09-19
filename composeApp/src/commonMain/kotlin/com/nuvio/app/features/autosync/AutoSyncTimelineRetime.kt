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
)
