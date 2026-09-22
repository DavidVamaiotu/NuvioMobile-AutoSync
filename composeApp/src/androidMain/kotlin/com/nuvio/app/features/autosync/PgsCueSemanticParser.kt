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
    val end: Long?,
    val timestampTicks: Long,
    val firstBlockPosition: Long?,
)

internal data class PgsSegmentProbe(
    val cueIndex: Int,
    val startTimeMs: Long,
    val durationMs: Long?,
    val segment: PgsSegmentInfo,
)

internal sealed interface PgsSegmentInfo {
    data class Presentation(
        val state: Int,
        val paletteUpdate: Boolean,
        val paletteId: Int,
        val objects: List<ObjectRef>,
    ) : PgsSegmentInfo

    data class Palette(
        val id: Int,
        val version: Int,
    ) : PgsSegmentInfo

    data class ObjectData(
        val id: Int,
        val version: Int,
        val sequence: Int,
    ) : PgsSegmentInfo

    data class Window(
        val definitions: List<WindowDefinition>,
    ) : PgsSegmentInfo

    data object End : PgsSegmentInfo

    data class ObjectRef(
        val objectId: Int,
        val windowId: Int,
        val x: Int,
        val y: Int,
    )

    data class WindowDefinition(
        val id: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )
}

/**
 * Pure parser for the small parts of Matroska/PGS that AutoSync needs.
 *
 * Matroska stores each S_HDMV/PGS segment in its own Block. The loader supplies those Blocks in
 * indexed order; this parser validates their container timestamp, reads only segment metadata, and
 * assembles PCS/PDS/ODS/WDS/END Blocks into semantic visibility intervals. Bitmap/RLE payloads are
 * never decoded or allocated.
 */
internal object PgsCueSemanticParser {
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L

    private const val PGS_PALETTE_SEGMENT = 0x14
    private const val PGS_OBJECT_SEGMENT = 0x15
    private const val PGS_PRESENTATION_SEGMENT = 0x16
    private const val PGS_WINDOW_SEGMENT = 0x17
    private const val PGS_END_SEGMENT = 0x80

    private const val PGS_CROPPED_FLAG = 0x80
    private const val PGS_OBJECT_FIRST = 0x80
    private const val PGS_OBJECT_LAST = 0x40

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

        val clusterEnd = root.size?.let { size ->
            if (clusterStart > Long.MAX_VALUE - root.dataStart.toLong() ||
                clusterStart + root.dataStart > Long.MAX_VALUE - size
            ) {
                return Result.failure(ParseException("cluster-size-overflow"))
            }
            clusterStart + root.dataStart + size
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
                end = clusterEnd,
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

    fun parseSegmentWindow(
        reference: IndexedPgsReference,
        locator: PgsCueLocator,
        cluster: PgsClusterInfo,
        blockPosition: Long,
        bytes: ByteArray,
        cueIndex: Int,
    ): Result<PgsSegmentProbe> {
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
        val blockHeaderBytes = track.length + 3
        if (blockSize <= blockHeaderBytes.toLong() || payloadOffset >= bytes.size) {
            return Result.failure(ParseException("missing-pgs-payload"))
        }

        val payloadSize = blockSize - blockHeaderBytes
        val segmentHeader = parseSegmentHeader(bytes, payloadOffset)
            ?: return Result.failure(ParseException("invalid-pgs-segment-header"))
        val expectedPayloadSize = segmentHeader.headerSize.toLong() + segmentHeader.dataLength
        if (expectedPayloadSize != payloadSize) {
            return Result.failure(
                ParseException(
                    "pgs-segment-size-mismatch block=$payloadSize segment=$expectedPayloadSize",
                ),
            )
        }

        val dataOffset = payloadOffset + segmentHeader.headerSize
        val segment = when (segmentHeader.type) {
            PGS_PRESENTATION_SEGMENT ->
                parsePresentation(bytes, dataOffset, segmentHeader.dataLength.toInt())
                    ?: return Result.failure(ParseException("malformed-pcs"))

            PGS_PALETTE_SEGMENT ->
                parsePalette(bytes, dataOffset, segmentHeader.dataLength.toInt())
                    ?: return Result.failure(ParseException("malformed-pds"))

            PGS_OBJECT_SEGMENT ->
                parseObject(bytes, dataOffset, segmentHeader.dataLength.toInt())
                    ?: return Result.failure(ParseException("malformed-ods"))

            PGS_WINDOW_SEGMENT ->
                parseWindow(bytes, dataOffset, segmentHeader.dataLength.toInt())
                    ?: return Result.failure(ParseException("malformed-wds"))

            PGS_END_SEGMENT -> {
                if (segmentHeader.dataLength != 0L) {
                    return Result.failure(ParseException("malformed-end-segment"))
                }
                PgsSegmentInfo.End
            }

            else -> return Result.failure(
                ParseException(
                    "unsupported-pgs-segment=0x${segmentHeader.type.toString(16)}",
                ),
            )
        }

        return Result.success(
            PgsSegmentProbe(
                cueIndex = cueIndex,
                startTimeMs = locator.startTimeMs,
                durationMs = locator.durationMs,
                segment = segment,
            ),
        )
    }

    fun buildTimeline(
        reference: IndexedPgsReference,
        probes: List<PgsSegmentProbe>,
    ): PgsReferenceResolution {
        if (probes.size != reference.cues.size) {
            return unavailable("incomplete-cue-coverage", cacheable = false)
        }

        val ordered = probes.sortedBy { it.cueIndex }
        if (ordered.indices.any { ordered[it].cueIndex != it }) {
            return unavailable("non-contiguous-cue-coverage", cacheable = false)
        }

        val builder = TimelineBuilder()
        for (probe in ordered) {
            val error = builder.consume(probe)
            if (error != null) {
                return unavailable(error, cacheable = true)
            }
        }

        val cues = when (val result = builder.finish()) {
            is TimelineResult.Unavailable ->
                return unavailable(result.reason, cacheable = true)
            is TimelineResult.Ready -> result.cues
        }

        if (cues.size < 3) {
            return unavailable("too-few-semantic-cues count=${cues.size}", cacheable = true)
        }

        return PgsReferenceResolution.Ready(
            ReferenceTrack(
                key = reference.key,
                language = reference.language,
                cues = cues,
                label = reference.label,
                selectionFlags = reference.selectionFlags,
                roleFlags = reference.roleFlags,
                generation = -1L,
                estimatedEndStartsMs = emptySet(),
            ),
        )
    }

    private class TimelineBuilder {
        private val cues = mutableListOf<SubtitleSyncCue>()
        private val paletteVersions = mutableMapOf<Int, Int>()
        private val objectVersions = mutableMapOf<Int, Int>()
        private val partialObjects = mutableMapOf<Int, Int>()
        private val windows = mutableMapOf<Int, PgsSegmentInfo.WindowDefinition>()

        private var pending: PendingPresentation? = null
        private var active: ActivePresentation? = null

        fun consume(probe: PgsSegmentProbe): String? {
            return when (val segment = probe.segment) {
                is PgsSegmentInfo.Presentation -> {
                    if (pending != null) return "pcs-before-end"

                    // PGS composition state uses the top two bits. Any non-normal state is an
                    // acquisition/epoch boundary, so previously cached graphics state is invalid.
                    if (segment.state != 0) {
                        paletteVersions.clear()
                        objectVersions.clear()
                        partialObjects.clear()
                        windows.clear()
                    }

                    pending = PendingPresentation(
                        startTimeMs = probe.startTimeMs,
                        presentation = segment,
                    )
                    null
                }

                is PgsSegmentInfo.Palette -> {
                    if (pending == null) return "palette-outside-display-set"
                    paletteVersions[segment.id] = segment.version
                    null
                }

                is PgsSegmentInfo.ObjectData -> {
                    if (pending == null) return "object-outside-display-set"
                    val first = (segment.sequence and PGS_OBJECT_FIRST) != 0
                    val last = (segment.sequence and PGS_OBJECT_LAST) != 0
                    when {
                        first && last -> {
                            partialObjects.remove(segment.id)
                            objectVersions[segment.id] = segment.version
                        }

                        first -> partialObjects[segment.id] = segment.version

                        last -> {
                            if (partialObjects[segment.id] != segment.version) {
                                return "orphan-object-tail id=${segment.id}"
                            }
                            partialObjects.remove(segment.id)
                            objectVersions[segment.id] = segment.version
                        }

                        partialObjects[segment.id] != segment.version ->
                            return "orphan-object-middle id=${segment.id}"
                    }
                    null
                }

                is PgsSegmentInfo.Window -> {
                    if (pending == null) return "window-outside-display-set"
                    for (definition in segment.definitions) {
                        windows[definition.id] = definition
                    }
                    null
                }

                PgsSegmentInfo.End -> {
                    val current = pending ?: return "end-without-pcs"
                    val error = commit(current)
                    if (error == null) pending = null
                    error
                }
            }
        }

        private fun commit(pending: PendingPresentation): String? {
            val presentation = pending.presentation
            val timeMs = pending.startTimeMs

            if (presentation.objects.isEmpty()) {
                closeActive(timeMs)
                return null
            }

            if (presentation.paletteUpdate) {
                return "palette-update-unsupported"
            }

            val paletteVersion = paletteVersions[presentation.paletteId]
                ?: return "missing-palette id=${presentation.paletteId}"
            val objectSignatures = ArrayList<ObjectSignature>(presentation.objects.size)

            for (ref in presentation.objects) {
                val version = objectVersions[ref.objectId]
                    ?: return "missing-object id=${ref.objectId}"
                if (partialObjects.containsKey(ref.objectId)) {
                    return "incomplete-object id=${ref.objectId}"
                }
                val window = windows[ref.windowId]
                objectSignatures += ObjectSignature(
                    objectId = ref.objectId,
                    version = version,
                    windowId = ref.windowId,
                    x = ref.x,
                    y = ref.y,
                    window = window,
                )
            }

            val signature = PresentationSignature(
                paletteId = presentation.paletteId,
                paletteVersion = paletteVersion,
                objects = objectSignatures,
            )
            val current = active
            val forceReplacement = presentation.state != 0
            if (current != null && !forceReplacement && current.signature == signature) {
                return null
            }

            closeActive(timeMs)
            active = ActivePresentation(
                startTimeMs = timeMs,
                signature = signature,
            )
            return null
        }

        private fun closeActive(endTimeMs: Long) {
            val current = active ?: return
            if (endTimeMs > current.startTimeMs) {
                cues += SubtitleSyncCue(
                    startTimeMs = current.startTimeMs,
                    endTimeMs = endTimeMs,
                    text = "",
                )
            }
            active = null
        }

        fun finish(): TimelineResult {
            if (pending != null) {
                return TimelineResult.Unavailable("display-set-missing-end")
            }
            if (partialObjects.isNotEmpty()) {
                return TimelineResult.Unavailable("incomplete-object-sequence")
            }

            if (active != null) {
                return TimelineResult.Unavailable("unresolved-final-presentation")
            }

            return TimelineResult.Ready(
                cues = cues
                    .sortedBy { it.startTimeMs }
                    .filter { it.endTimeMs > it.startTimeMs }
                    .distinctBy { it.startTimeMs to it.endTimeMs },
            )
        }
    }

    private fun parseSegmentHeader(
        bytes: ByteArray,
        offset: Int,
    ): SegmentHeader? {
        if (offset < 0 || offset + 3 > bytes.size) return null

        val hasSupHeader =
            offset + 13 <= bytes.size &&
                bytes[offset].toInt() == 0x50 &&
                bytes[offset + 1].toInt() == 0x47
        val typeOffset = if (hasSupHeader) offset + 10 else offset
        val headerSize = if (hasSupHeader) 13 else 3
        if (typeOffset + 2 >= bytes.size) return null

        val type = bytes[typeOffset].toInt() and 0xFF
        val dataLength =
            ((bytes[typeOffset + 1].toLong() and 0xFFL) shl 8) or
                (bytes[typeOffset + 2].toLong() and 0xFFL)
        return SegmentHeader(
            type = type,
            dataLength = dataLength,
            headerSize = headerSize,
        )
    }

    private fun parsePresentation(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): PgsSegmentInfo.Presentation? {
        if (length < 11 || offset < 0 || offset + length > bytes.size) return null

        val state = (bytes[offset + 7].toInt() and 0xFF) ushr 6
        val paletteUpdate = (bytes[offset + 8].toInt() and 0x80) != 0
        val paletteId = bytes[offset + 9].toInt() and 0xFF
        val objectCount = bytes[offset + 10].toInt() and 0xFF

        var objectOffset = offset + 11
        val end = offset + length
        val objects = ArrayList<PgsSegmentInfo.ObjectRef>(objectCount)
        repeat(objectCount) {
            if (objectOffset + 8 > end) return null
            val objectId = readUInt16(bytes, objectOffset)
            val windowId = bytes[objectOffset + 2].toInt() and 0xFF
            val flags = bytes[objectOffset + 3].toInt() and 0xFF
            if ((flags and PGS_CROPPED_FLAG) != 0) return null
            objects += PgsSegmentInfo.ObjectRef(
                objectId = objectId,
                windowId = windowId,
                x = readUInt16(bytes, objectOffset + 4),
                y = readUInt16(bytes, objectOffset + 6),
            )
            objectOffset += 8
        }
        return PgsSegmentInfo.Presentation(
            state = state,
            paletteUpdate = paletteUpdate,
            paletteId = paletteId,
            objects = objects,
        )
    }

    private fun parsePalette(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): PgsSegmentInfo.Palette? {
        if (length < 2 || offset < 0 || offset + 2 > bytes.size) return null
        return PgsSegmentInfo.Palette(
            id = bytes[offset].toInt() and 0xFF,
            version = bytes[offset + 1].toInt() and 0xFF,
        )
    }

    private fun parseObject(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): PgsSegmentInfo.ObjectData? {
        if (length < 4 || offset < 0 || offset + 4 > bytes.size) return null
        return PgsSegmentInfo.ObjectData(
            id = readUInt16(bytes, offset),
            version = bytes[offset + 2].toInt() and 0xFF,
            sequence = bytes[offset + 3].toInt() and 0xFF,
        )
    }

    private fun parseWindow(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): PgsSegmentInfo.Window? {
        if (length < 1 || offset < 0 || offset >= bytes.size) return null
        val count = bytes[offset].toInt() and 0xFF
        if (length != 1 + count * 9 || offset + length > bytes.size) return null

        var position = offset + 1
        val definitions = ArrayList<PgsSegmentInfo.WindowDefinition>(count)
        repeat(count) {
            definitions += PgsSegmentInfo.WindowDefinition(
                id = bytes[position].toInt() and 0xFF,
                x = readUInt16(bytes, position + 1),
                y = readUInt16(bytes, position + 3),
                width = readUInt16(bytes, position + 5),
                height = readUInt16(bytes, position + 7),
            )
            position += 9
        }
        return PgsSegmentInfo.Window(definitions)
    }

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

    internal fun readElementHeader(
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

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun ticksToMs(ticks: Long, scaleNs: Long): Long? {
        if (ticks < 0L || scaleNs <= 0L) return null
        val whole = ticks / 1_000_000L
        val remainder = ticks % 1_000_000L
        if (whole > Long.MAX_VALUE / scaleNs) return null
        val wholeMs = whole * scaleNs
        val remainderNs = remainder * scaleNs
        return wholeMs + remainderNs / 1_000_000L
    }

    private fun unavailable(reason: String, cacheable: Boolean) =
        PgsReferenceResolution.Unavailable(reason = reason, cacheable = cacheable)

    internal data class ElementHeader(
        val id: Long,
        val size: Long?,
        val headerStart: Int,
        val dataStart: Int,
    )

    private data class VintValue(
        val value: Long,
        val length: Int,
    )

    private data class SegmentHeader(
        val type: Int,
        val dataLength: Long,
        val headerSize: Int,
    )

    private data class PendingPresentation(
        val startTimeMs: Long,
        val presentation: PgsSegmentInfo.Presentation,
    )

    private data class ObjectSignature(
        val objectId: Int,
        val version: Int,
        val windowId: Int,
        val x: Int,
        val y: Int,
        val window: PgsSegmentInfo.WindowDefinition?,
    )

    private data class PresentationSignature(
        val paletteId: Int,
        val paletteVersion: Int,
        val objects: List<ObjectSignature>,
    )

    private data class ActivePresentation(
        val startTimeMs: Long,
        val signature: PresentationSignature,
    )

    private sealed interface TimelineResult {
        data class Ready(val cues: List<SubtitleSyncCue>) : TimelineResult
        data class Unavailable(val reason: String) : TimelineResult
    }

    private class ParseException(message: String) : Exception(message)
}
