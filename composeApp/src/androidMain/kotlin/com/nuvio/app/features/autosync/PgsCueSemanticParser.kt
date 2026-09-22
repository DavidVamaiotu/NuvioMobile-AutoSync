package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs

internal data class IndexedPgsReference(
    val key: String,
    val language: String?,
    val label: String?,
    val selectionFlags: Int,
    val roleFlags: Int,
    val trackNumber: Int,
    val segmentDataStart: Long,
    val timestampScaleNs: Long,
    val cues: List<PgsCueLocator>,
    val unsupportedReason: String? = null,
) {
    fun previewTrack(): ReferenceTrack = ReferenceTrack(
        key = key,
        language = language,
        cues = cues.map { cue ->
            SubtitleSyncCue(
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.startTimeMs + (cue.durationMs ?: 1L).coerceAtLeast(1L),
                text = "",
            )
        },
        label = label,
        selectionFlags = selectionFlags,
        roleFlags = roleFlags,
        generation = -1L,
    )
}

internal data class PgsCueLocator(
    val startTimeMs: Long,
    val cueTimeTicks: Long,
    val durationMs: Long?,
    val clusterPosition: Long,
    val relativePosition: Long?,
    val blockNumber: Long?,
)

internal sealed interface PgsReferenceResolution {
    data class Ready(val track: ReferenceTrack) : PgsReferenceResolution

    data class Unavailable(
        val reason: String,
        val cacheable: Boolean,
    ) : PgsReferenceResolution
}

internal data class PgsClusterInfo(
    val clusterStart: Long,
    val dataStart: Long,
    val timestampTicks: Long,
    val firstBlockPosition: Long?,
)

internal data class PgsPresentationProbe(
    val cueIndex: Int,
    val startTimeMs: Long,
    val durationMs: Long?,
    val visible: Boolean,
    val payloadEnd: Long,
)

/**
 * Pure parser for the small parts of Matroska/PGS that AutoSync needs.
 *
 * Matroska PGS subtitle packets are display sets. AutoSync does not need palette or bitmap data:
 * it only needs to know whether a presentation is visible and where the display set ends. The
 * loader therefore fetches sparse Cluster/Block windows while this parser validates EBML, track
 * identity, timestamps, PCS semantics, cropping support and the final PGS END segment.
 */
internal object PgsCueSemanticParser {
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L

    private const val PGS_PRESENTATION_SEGMENT = 0x16
    private const val PGS_END_SEGMENT = 0x80
    private const val PGS_CROPPED_FLAG = 0x80

    fun parseClusterWindow(
        reference: IndexedPgsReference,
        clusterStart: Long,
        bytes: ByteArray,
    ): Result<PgsClusterInfo> {
        val root = readElementHeader(bytes, 0)
            ?: return Result.failure(ParseException("invalid-cluster-header"))
        if (root.id != ID_CLUSTER) {
            return Result.failure(ParseException("expected-cluster"))
        }

        var position = root.dataStart
        var timestampTicks: Long? = null
        var firstBlockPosition: Long? = null
        var children = 0

        while (position < bytes.size && children++ < 64) {
            val child = readElementHeader(bytes, position) ?: break
            when (child.id) {
                ID_CLUSTER_TIMESTAMP ->
                    timestampTicks = readUnsigned(bytes, child)

                ID_SIMPLE_BLOCK, ID_BLOCK_GROUP -> {
                    firstBlockPosition = clusterStart + child.headerStart
                    break
                }
            }

            val size = child.size ?: break
            val next = child.dataStart.toLong() + size
            if (next <= position.toLong() || next > bytes.size.toLong()) break
            position = next.toInt()
        }

        return Result.success(
            PgsClusterInfo(
                clusterStart = clusterStart,
                dataStart = clusterStart + root.dataStart,
                timestampTicks = timestampTicks
                    ?: return Result.failure(ParseException("cluster-timestamp-unavailable")),
                firstBlockPosition = firstBlockPosition,
            ),
        )
    }

    fun blockPosition(
        locator: PgsCueLocator,
        cluster: PgsClusterInfo,
    ): Result<Long> {
        val relative = locator.relativePosition
        if (relative != null) {
            if (relative < 0L || cluster.dataStart > Long.MAX_VALUE - relative) {
                return Result.failure(ParseException("invalid-relative-position"))
            }
            return Result.success(cluster.dataStart + relative)
        }

        val blockNumber = locator.blockNumber ?: 1L
        if (blockNumber == 1L && cluster.firstBlockPosition != null) {
            return Result.success(cluster.firstBlockPosition)
        }
        return Result.failure(ParseException("cue-block-number-requires-scan"))
    }

    fun parsePresentationWindow(
        reference: IndexedPgsReference,
        locator: PgsCueLocator,
        cluster: PgsClusterInfo,
        blockPosition: Long,
        bytes: ByteArray,
        cueIndex: Int,
    ): Result<PgsPresentationProbe> {
        val root = readElementHeader(bytes, 0)
            ?: return Result.failure(ParseException("invalid-block-element"))

        val block = when (root.id) {
            ID_SIMPLE_BLOCK -> root
            ID_BLOCK_GROUP -> findBlockInGroup(bytes, root)
                ?: return Result.failure(ParseException("blockgroup-block-not-in-prefix"))
            else -> return Result.failure(
                ParseException("cue-position-not-block id=0x${root.id.toString(16)}"),
            )
        }

        val blockSize = block.size
            ?: return Result.failure(ParseException("unknown-block-size"))
        if (blockSize < 4L) {
            return Result.failure(ParseException("short-block"))
        }

        val dataStart = block.dataStart
        val track = readVintValue(bytes, dataStart)
            ?: return Result.failure(ParseException("invalid-track-vint"))
        if (track.value != reference.trackNumber.toLong()) {
            return Result.failure(
                ParseException(
                    "wrong-track expected=${reference.trackNumber} actual=${track.value}",
                ),
            )
        }

        val timecodeOffset = dataStart + track.length
        if (timecodeOffset + 2 >= bytes.size) {
            return Result.failure(ParseException("short-block-header"))
        }
        val relativeTicks = readSignedInt16(bytes, timecodeOffset)
        val flags = bytes[timecodeOffset + 2].toInt() and 0xFF
        if ((flags and 0x06) != 0) {
            return Result.failure(ParseException("laced-pgs-block"))
        }

        val absoluteTicks = cluster.timestampTicks + relativeTicks
        val blockTimeMs = ticksToMs(absoluteTicks, reference.timestampScaleNs)
            ?: return Result.failure(ParseException("timestamp-overflow"))
        if (abs(blockTimeMs - locator.startTimeMs) > 2L) {
            return Result.failure(
                ParseException(
                    "block-time-mismatch cue=${locator.startTimeMs} block=$blockTimeMs",
                ),
            )
        }

        val payloadOffset = timecodeOffset + 3
        val blockDataStartAbsolute = blockPosition + block.dataStart
        if (blockDataStartAbsolute > Long.MAX_VALUE - blockSize) {
            return Result.failure(ParseException("block-size-overflow"))
        }
        val payloadEnd = blockDataStartAbsolute + blockSize
        if (payloadOffset >= bytes.size) {
            return Result.failure(ParseException("missing-pgs-payload"))
        }

        val pcs = parsePcs(bytes, payloadOffset)
            ?: return Result.failure(ParseException("display-set-does-not-start-with-pcs"))

        return Result.success(
            PgsPresentationProbe(
                cueIndex = cueIndex,
                startTimeMs = locator.startTimeMs,
                durationMs = locator.durationMs,
                visible = pcs.objectCount > 0,
                payloadEnd = payloadEnd,
            ),
        )
    }

    fun hasDisplayEnd(bytes: ByteArray): Boolean {
        if (bytes.size >= 3) {
            val offset = bytes.size - 3
            if ((bytes[offset].toInt() and 0xFF) == PGS_END_SEGMENT &&
                bytes[offset + 1].toInt() == 0 &&
                bytes[offset + 2].toInt() == 0
            ) {
                return true
            }
        }

        if (bytes.size >= 13) {
            val offset = bytes.size - 13
            return bytes[offset].toInt() == 0x50 &&
                bytes[offset + 1].toInt() == 0x47 &&
                (bytes[offset + 10].toInt() and 0xFF) == PGS_END_SEGMENT &&
                bytes[offset + 11].toInt() == 0 &&
                bytes[offset + 12].toInt() == 0
        }
        return false
    }

    fun buildTimeline(
        reference: IndexedPgsReference,
        probes: List<PgsPresentationProbe>,
    ): PgsReferenceResolution {
        if (probes.size != reference.cues.size) {
            return unavailable("incomplete-cue-coverage", cacheable = false)
        }

        val ordered = probes.sortedWith(
            compareBy<PgsPresentationProbe> { it.startTimeMs }
                .thenBy { it.cueIndex },
        )
        if (ordered.zipWithNext().any { (left, right) ->
                right.startTimeMs < left.startTimeMs
            }
        ) {
            return unavailable("non-monotonic-pgs-timeline", cacheable = true)
        }

        val cues = mutableListOf<SubtitleSyncCue>()
        ordered.forEachIndexed { index, probe ->
            if (!probe.visible) return@forEachIndexed

            val nextStart = ordered.getOrNull(index + 1)?.startTimeMs
            val explicitEnd = probe.durationMs
                ?.takeIf { duration ->
                    duration > 0L && probe.startTimeMs <= Long.MAX_VALUE - duration
                }
                ?.let { duration -> probe.startTimeMs + duration }

            val end = when {
                explicitEnd != null && nextStart != null -> minOf(explicitEnd, nextStart)
                explicitEnd != null -> explicitEnd
                nextStart != null -> nextStart
                else -> return unavailable(
                    "unresolved-final-presentation",
                    cacheable = true,
                )
            }

            if (end <= probe.startTimeMs) {
                return@forEachIndexed
            }
            cues += SubtitleSyncCue(
                startTimeMs = probe.startTimeMs,
                endTimeMs = end,
                text = "",
            )
        }

        if (cues.size < 3) {
            return unavailable("too-few-semantic-cues count=${cues.size}", cacheable = true)
        }

        return PgsReferenceResolution.Ready(
            ReferenceTrack(
                key = reference.key,
                language = reference.language,
                cues = cues
                    .sortedBy { it.startTimeMs }
                    .distinctBy { it.startTimeMs to it.endTimeMs },
                label = reference.label,
                selectionFlags = reference.selectionFlags,
                roleFlags = reference.roleFlags,
                generation = -1L,
                estimatedEndStartsMs = emptySet(),
            ),
        )
    }

    private fun unavailable(reason: String, cacheable: Boolean) =
        PgsReferenceResolution.Unavailable(reason = reason, cacheable = cacheable)

    private fun findBlockInGroup(
        bytes: ByteArray,
        group: ElementHeader,
    ): ElementHeader? {
        val groupSize = group.size ?: return null
        val declaredEnd = group.dataStart.toLong() + groupSize
        var position = group.dataStart
        var children = 0

        while (position < bytes.size &&
            position.toLong() < declaredEnd &&
            children++ < 32
        ) {
            val child = readElementHeader(bytes, position) ?: return null
            if (child.id == ID_BLOCK) return child

            val size = child.size ?: return null
            val next = child.dataStart.toLong() + size
            if (next <= position.toLong() || next > bytes.size.toLong()) return null
            position = next.toInt()
        }
        return null
    }

    private fun parsePcs(
        bytes: ByteArray,
        offset: Int,
    ): Pcs? {
        val supHeader =
            offset + 13 <= bytes.size &&
                bytes[offset].toInt() == 0x50 &&
                bytes[offset + 1].toInt() == 0x47
        val typeOffset = if (supHeader) offset + 10 else offset
        val headerSize = if (supHeader) 13 else 3
        if (typeOffset + 2 >= bytes.size || offset + headerSize > bytes.size) return null
        if ((bytes[typeOffset].toInt() and 0xFF) != PGS_PRESENTATION_SEGMENT) return null

        val dataLength =
            ((bytes[typeOffset + 1].toInt() and 0xFF) shl 8) or
                (bytes[typeOffset + 2].toInt() and 0xFF)
        val dataStart = offset + headerSize
        if (dataLength < 11 || dataStart + dataLength > bytes.size) return null

        val objectCount = bytes[dataStart + 10].toInt() and 0xFF
        var objectOffset = dataStart + 11
        repeat(objectCount) {
            if (objectOffset + 8 > dataStart + dataLength) return null
            val flags = bytes[objectOffset + 3].toInt() and 0xFF
            if ((flags and PGS_CROPPED_FLAG) != 0) return null
            objectOffset += 8
        }

        return Pcs(objectCount = objectCount)
    }

    private fun readElementHeader(
        bytes: ByteArray,
        offset: Int,
    ): ElementHeader? {
        if (offset !in bytes.indices) return null
        val idLength = vintLength(bytes[offset].toInt() and 0xFF) ?: return null
        if (idLength > 4 || offset + idLength >= bytes.size) return null

        var id = 0L
        for (index in 0 until idLength) {
            id = (id shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }

        val sizeOffset = offset + idLength
        val sizeLength = vintLength(bytes[sizeOffset].toInt() and 0xFF) ?: return null
        if (sizeLength > 8 || sizeOffset + sizeLength > bytes.size) return null

        val markerMask = 1 shl (8 - sizeLength)
        var sizeValue = (bytes[sizeOffset].toInt() and (markerMask - 1)).toLong()
        for (index in 1 until sizeLength) {
            sizeValue = (sizeValue shl 8) or (bytes[sizeOffset + index].toLong() and 0xFFL)
        }
        val unknownValue = (1L shl (7 * sizeLength)) - 1L

        return ElementHeader(
            id = id,
            size = sizeValue.takeUnless { it == unknownValue },
            headerStart = offset,
            dataStart = sizeOffset + sizeLength,
        )
    }

    private fun readUnsigned(
        bytes: ByteArray,
        element: ElementHeader,
    ): Long? {
        val size = element.size?.takeIf { it in 1L..8L }?.toInt() ?: return null
        val end = element.dataStart + size
        if (end > bytes.size) return null
        var value = 0L
        for (index in element.dataStart until end) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readVintValue(
        bytes: ByteArray,
        offset: Int,
    ): VintValue? {
        if (offset !in bytes.indices) return null
        val length = vintLength(bytes[offset].toInt() and 0xFF) ?: return null
        if (length > 8 || offset + length > bytes.size) return null

        val markerMask = 1 shl (8 - length)
        var value = (bytes[offset].toInt() and (markerMask - 1)).toLong()
        for (index in 1 until length) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return VintValue(value = value, length = length)
    }

    private fun vintLength(firstByte: Int): Int? {
        if (firstByte == 0) return null
        var mask = 0x80
        var length = 1
        while ((firstByte and mask) == 0) {
            mask = mask ushr 1
            length++
            if (length > 8) return null
        }
        return length
    }

    private fun readSignedInt16(bytes: ByteArray, offset: Int): Long {
        val value =
            ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
        return value.toShort().toLong()
    }

    private fun ticksToMs(ticks: Long, scaleNs: Long): Long? {
        if (ticks < 0L || scaleNs <= 0L) return null
        val whole = ticks / 1_000_000L
        val remainder = ticks % 1_000_000L
        if (whole > Long.MAX_VALUE / scaleNs) return null
        val wholeMs = whole * scaleNs
        val remainderNs = remainder * scaleNs
        return wholeMs + remainderNs / 1_000_000L
    }

    private data class ElementHeader(
        val id: Long,
        val size: Long?,
        val headerStart: Int,
        val dataStart: Int,
    )

    private data class VintValue(
        val value: Long,
        val length: Int,
    )

    private data class Pcs(
        val objectCount: Int,
    )

    private class ParseException(message: String) : Exception(message)
}
