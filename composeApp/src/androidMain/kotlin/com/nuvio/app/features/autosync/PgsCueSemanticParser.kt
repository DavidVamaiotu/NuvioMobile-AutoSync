package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.max

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

internal fun interface PgsRangeReader {
    suspend fun read(start: Long, length: Int): ByteArray?
}

/**
 * Resolves Matroska S_HDMV/PGS index entries into semantic subtitle visibility intervals.
 *
 * This deliberately does not decode bitmaps. It reads only container/block headers and the small
 * PGS control headers needed for PCS/PDS/ODS/END state. Every indexed PGS packet must resolve; an
 * incomplete, compressed, laced, cropped, malformed or budget-limited track is rejected instead
 * of being converted into guessed AutoSync cues.
 */
internal object PgsCueSemanticParser {
    private const val PAGE_BYTES = 16 * 1024
    private const val MAX_CACHED_PAGES = 12
    private const val MAX_CLUSTER_CHILDREN = 200_000
    private const val MAX_BLOCK_GROUP_CHILDREN = 128
    private const val MAX_SEGMENTS_PER_SAMPLE = 128
    private const val MAX_PCS_BYTES = 4 * 1024

    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private const val PGS_PALETTE_SEGMENT = 0x14
    private const val PGS_OBJECT_SEGMENT = 0x15
    private const val PGS_PRESENTATION_SEGMENT = 0x16
    private const val PGS_WINDOW_SEGMENT = 0x17
    private const val PGS_END_SEGMENT = 0x80

    private const val PGS_EPOCH_START = 0x80
    private const val PGS_CROPPED_FLAG = 0x80
    private const val PGS_OBJECT_FIRST = 0x80
    private const val PGS_OBJECT_LAST = 0x40

    suspend fun resolve(
        reference: IndexedPgsReference,
        rangeReader: PgsRangeReader,
    ): PgsReferenceResolution {
        reference.unsupportedReason?.let { reason ->
            return unavailable(reason, cacheable = true)
        }
        if (reference.cues.isEmpty()) {
            return unavailable("no-indexed-pgs-cues", cacheable = true)
        }

        val reader = PagedReader(rangeReader)
        val timeline = TimelineBuilder()
        val clusterCache = mutableMapOf<Long, ClusterInfo>()

        val ordered = reference.cues
            .sortedWith(
                compareBy<PgsCueLocator> { it.startTimeMs }
                    .thenBy { it.clusterPosition }
                    .thenBy { it.relativePosition ?: Long.MAX_VALUE }
                    .thenBy { it.blockNumber ?: Long.MAX_VALUE },
            )
            .distinctBy {
                LocatorIdentity(
                    clusterPosition = it.clusterPosition,
                    relativePosition = it.relativePosition,
                    blockNumber = it.blockNumber,
                    cueTimeTicks = it.cueTimeTicks,
                )
            }

        for ((index, locator) in ordered.withIndex()) {
            val cluster = clusterCache[locator.clusterPosition]
                ?: resolveCluster(reference, locator.clusterPosition, reader)?.also {
                    clusterCache[locator.clusterPosition] = it
                }
                ?: return unavailable(
                    "cluster-unavailable index=$index position=${locator.clusterPosition}",
                    cacheable = false,
                )

            val blockPosition = when {
                locator.relativePosition != null -> {
                    val relative = locator.relativePosition
                    if (relative < 0L || cluster.dataStart > Long.MAX_VALUE - relative) {
                        return unavailable("invalid-relative-position index=$index", cacheable = true)
                    }
                    cluster.dataStart + relative
                }

                else -> {
                    val blockNumber = locator.blockNumber ?: 1L
                    findBlockByNumber(
                        cluster = cluster,
                        blockNumber = blockNumber,
                        reader = reader,
                    ) ?: return unavailable(
                        "block-number-unresolved index=$index block=$blockNumber",
                        cacheable = false,
                    )
                }
            }

            val sample = readContainerSample(
                reference = reference,
                locator = locator,
                cluster = cluster,
                blockPosition = blockPosition,
                reader = reader,
            )
            when (sample) {
                is SampleResult.Unavailable ->
                    return unavailable(sample.reason, sample.cacheable)

                is SampleResult.Ready -> {
                    val consumeError = timeline.consume(sample.sample)
                    if (consumeError != null) {
                        return unavailable(consumeError, cacheable = true)
                    }
                }
            }
        }

        val cues = when (val result = timeline.finish()) {
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

    private fun unavailable(reason: String, cacheable: Boolean) =
        PgsReferenceResolution.Unavailable(reason = reason, cacheable = cacheable)

    private suspend fun resolveCluster(
        reference: IndexedPgsReference,
        clusterPosition: Long,
        reader: PagedReader,
    ): ClusterInfo? {
        if (clusterPosition < 0L ||
            reference.segmentDataStart > Long.MAX_VALUE - clusterPosition
        ) {
            return null
        }
        val absolute = reference.segmentDataStart + clusterPosition
        val header = reader.readElementHeader(absolute) ?: return null
        if (header.id != ID_CLUSTER) return null

        val clusterEnd = header.size?.let { size ->
            if (header.dataStart > Long.MAX_VALUE - size) return null
            header.dataStart + size
        }

        var position = header.dataStart
        var timestampTicks: Long? = null
        var children = 0
        while (children++ < 64 && (clusterEnd == null || position < clusterEnd)) {
            val child = reader.readElementHeader(position) ?: break
            if (child.id == ID_CLUSTER_TIMESTAMP) {
                timestampTicks = reader.readUnsigned(child.dataStart, child.size) ?: return null
                break
            }
            if (child.id == ID_SIMPLE_BLOCK || child.id == ID_BLOCK_GROUP) break
            val size = child.size ?: return null
            if (child.dataStart > Long.MAX_VALUE - size) return null
            val next = child.dataStart + size
            if (next <= position) return null
            position = next
        }

        return ClusterInfo(
            start = absolute,
            dataStart = header.dataStart,
            end = clusterEnd,
            timestampTicks = timestampTicks ?: return null,
        )
    }

    private suspend fun findBlockByNumber(
        cluster: ClusterInfo,
        blockNumber: Long,
        reader: PagedReader,
    ): Long? {
        if (blockNumber <= 0L) return null
        val clusterEnd = cluster.end ?: return null
        var position = cluster.dataStart
        var seenBlocks = 0L
        var children = 0

        while (position < clusterEnd && children++ < MAX_CLUSTER_CHILDREN) {
            val child = reader.readElementHeader(position) ?: return null
            if (child.id == ID_SIMPLE_BLOCK || child.id == ID_BLOCK_GROUP) {
                seenBlocks++
                if (seenBlocks == blockNumber) return position
            }
            val size = child.size ?: return null
            if (child.dataStart > Long.MAX_VALUE - size) return null
            val next = child.dataStart + size
            if (next <= position || next > clusterEnd) return null
            position = next
        }
        return null
    }

    private suspend fun readContainerSample(
        reference: IndexedPgsReference,
        locator: PgsCueLocator,
        cluster: ClusterInfo,
        blockPosition: Long,
        reader: PagedReader,
    ): SampleResult {
        val element = reader.readElementHeader(blockPosition)
            ?: return SampleResult.Unavailable("block-header-unavailable", cacheable = false)

        return when (element.id) {
            ID_SIMPLE_BLOCK -> {
                val size = element.size
                    ?: return SampleResult.Unavailable("unknown-simpleblock-size", cacheable = true)
                readBlockPayload(
                    reference = reference,
                    locator = locator,
                    cluster = cluster,
                    blockDataStart = element.dataStart,
                    blockDataSize = size,
                    durationMs = locator.durationMs,
                    reader = reader,
                )
            }

            ID_BLOCK_GROUP -> readBlockGroup(
                reference = reference,
                locator = locator,
                cluster = cluster,
                group = element,
                reader = reader,
            )

            else -> SampleResult.Unavailable(
                "cue-relative-position-not-block id=0x${element.id.toString(16)}",
                cacheable = true,
            )
        }
    }

    private suspend fun readBlockGroup(
        reference: IndexedPgsReference,
        locator: PgsCueLocator,
        cluster: ClusterInfo,
        group: ElementHeader,
        reader: PagedReader,
    ): SampleResult {
        val groupSize = group.size
            ?: return SampleResult.Unavailable("unknown-blockgroup-size", cacheable = true)
        if (group.dataStart > Long.MAX_VALUE - groupSize) {
            return SampleResult.Unavailable("blockgroup-overflow", cacheable = true)
        }
        val groupEnd = group.dataStart + groupSize

        var position = group.dataStart
        var block: ElementHeader? = null
        var durationTicks: Long? = null
        var children = 0
        while (position < groupEnd && children++ < MAX_BLOCK_GROUP_CHILDREN) {
            val child = reader.readElementHeader(position)
                ?: return SampleResult.Unavailable("blockgroup-child-unavailable", cacheable = false)
            when (child.id) {
                ID_BLOCK -> block = child
                ID_BLOCK_DURATION ->
                    durationTicks = reader.readUnsigned(child.dataStart, child.size)
            }
            val size = child.size
                ?: return SampleResult.Unavailable("unknown-blockgroup-child-size", cacheable = true)
            if (child.dataStart > Long.MAX_VALUE - size) {
                return SampleResult.Unavailable("blockgroup-child-overflow", cacheable = true)
            }
            val next = child.dataStart + size
            if (next <= position || next > groupEnd) {
                return SampleResult.Unavailable("invalid-blockgroup-child-size", cacheable = true)
            }
            position = next
        }

        val blockElement = block
            ?: return SampleResult.Unavailable("blockgroup-missing-block", cacheable = true)
        val blockSize = blockElement.size
            ?: return SampleResult.Unavailable("unknown-block-size", cacheable = true)
        val blockDurationMs = durationTicks
            ?.let { ticksToMs(it, reference.timestampScaleNs) }
            ?.takeIf { it > 0L }
            ?: locator.durationMs

        return readBlockPayload(
            reference = reference,
            locator = locator,
            cluster = cluster,
            blockDataStart = blockElement.dataStart,
            blockDataSize = blockSize,
            durationMs = blockDurationMs,
            reader = reader,
        )
    }

    private suspend fun readBlockPayload(
        reference: IndexedPgsReference,
        locator: PgsCueLocator,
        cluster: ClusterInfo,
        blockDataStart: Long,
        blockDataSize: Long,
        durationMs: Long?,
        reader: PagedReader,
    ): SampleResult {
        if (blockDataSize < 4L || blockDataStart > Long.MAX_VALUE - blockDataSize) {
            return SampleResult.Unavailable("invalid-block-size", cacheable = true)
        }
        val prefix = reader.read(blockDataStart, 16)
            ?: return SampleResult.Unavailable("block-prefix-unavailable", cacheable = false)
        val track = readVintValue(prefix, 0)
            ?: return SampleResult.Unavailable("invalid-block-track-vint", cacheable = true)
        if (track.value != reference.trackNumber.toLong()) {
            return SampleResult.Unavailable(
                "wrong-block-track expected=${reference.trackNumber} actual=${track.value}",
                cacheable = true,
            )
        }

        val timecodeOffset = track.length
        if (timecodeOffset + 2 >= prefix.size) {
            return SampleResult.Unavailable("short-block-header", cacheable = false)
        }
        val relativeTicks = readSignedInt16(prefix, timecodeOffset)
        val flags = prefix[timecodeOffset + 2].toInt() and 0xFF
        if ((flags and 0x06) != 0) {
            return SampleResult.Unavailable("laced-pgs-block", cacheable = true)
        }

        val absoluteTicks = cluster.timestampTicks + relativeTicks
        val presentationTimeMs = ticksToMs(absoluteTicks, reference.timestampScaleNs)
            ?: return SampleResult.Unavailable("timestamp-overflow", cacheable = true)
        if (abs(presentationTimeMs - locator.startTimeMs) > 250L) {
            return SampleResult.Unavailable(
                "block-time-mismatch cue=${locator.startTimeMs} block=$presentationTimeMs",
                cacheable = true,
            )
        }

        val headerBytes = track.length + 3
        if (blockDataSize <= headerBytes.toLong()) {
            return SampleResult.Unavailable("empty-pgs-block", cacheable = true)
        }
        val payloadStart = blockDataStart + headerBytes
        val payloadEnd = blockDataStart + blockDataSize

        val segments = readPgsSegments(
            payloadStart = payloadStart,
            payloadEnd = payloadEnd,
            presentationTimeMs = presentationTimeMs,
            durationMs = durationMs,
            reader = reader,
        )
        return when (segments) {
            is SegmentReadResult.Unavailable ->
                SampleResult.Unavailable(segments.reason, segments.cacheable)
            is SegmentReadResult.Ready ->
                SampleResult.Ready(
                    PgsContainerSample(
                        presentationTimeMs = presentationTimeMs,
                        durationMs = durationMs,
                        segments = segments.segments,
                    ),
                )
        }
    }

    private suspend fun readPgsSegments(
        payloadStart: Long,
        payloadEnd: Long,
        presentationTimeMs: Long,
        durationMs: Long?,
        reader: PagedReader,
    ): SegmentReadResult {
        var position = payloadStart
        var segmentCount = 0
        val result = mutableListOf<PgsSegment>()

        while (position < payloadEnd && segmentCount++ < MAX_SEGMENTS_PER_SAMPLE) {
            val available = (payloadEnd - position).coerceAtMost(16L).toInt()
            if (available < 3) {
                return SegmentReadResult.Unavailable("truncated-pgs-segment-header", true)
            }
            val header = reader.read(position, available)
                ?: return SegmentReadResult.Unavailable("pgs-segment-header-unavailable", false)

            val supHeader =
                header.size >= 13 &&
                    header[0].toInt() == 0x50 &&
                    header[1].toInt() == 0x47
            val typeOffset = if (supHeader) 10 else 0
            val headerSize = if (supHeader) 13 else 3
            if (header.size < headerSize) {
                return SegmentReadResult.Unavailable("short-pgs-segment-header", false)
            }

            val type = header[typeOffset].toInt() and 0xFF
            val lengthOffset = typeOffset + 1
            val dataLength =
                ((header[lengthOffset].toInt() and 0xFF) shl 8) or
                    (header[lengthOffset + 1].toInt() and 0xFF)
            val dataStart = position + headerSize
            val segmentEnd = dataStart + dataLength.toLong()
            if (segmentEnd < dataStart || segmentEnd > payloadEnd) {
                return SegmentReadResult.Unavailable("pgs-segment-outside-sample", true)
            }

            when (type) {
                PGS_PRESENTATION_SEGMENT -> {
                    if (dataLength < 11 || dataLength > MAX_PCS_BYTES) {
                        return SegmentReadResult.Unavailable("unsupported-pcs-size=$dataLength", true)
                    }
                    val data = reader.read(dataStart, dataLength)
                        ?: return SegmentReadResult.Unavailable("pcs-unavailable", false)
                    val pcs = parsePcs(
                        data = data,
                        presentationTimeMs = presentationTimeMs,
                        durationMs = durationMs,
                    ) ?: return SegmentReadResult.Unavailable("malformed-pcs", true)
                    result += pcs
                }

                PGS_PALETTE_SEGMENT -> {
                    if (dataLength < 2) {
                        return SegmentReadResult.Unavailable("malformed-pds", true)
                    }
                    val data = reader.read(dataStart, 2)
                        ?: return SegmentReadResult.Unavailable("pds-unavailable", false)
                    result += PgsSegment.Palette(
                        id = data[0].toInt() and 0xFF,
                        version = data[1].toInt() and 0xFF,
                    )
                }

                PGS_OBJECT_SEGMENT -> {
                    if (dataLength < 4) {
                        return SegmentReadResult.Unavailable("malformed-ods", true)
                    }
                    val data = reader.read(dataStart, 4)
                        ?: return SegmentReadResult.Unavailable("ods-unavailable", false)
                    result += PgsSegment.ObjectData(
                        id = readUInt16(data, 0),
                        version = data[2].toInt() and 0xFF,
                        sequence = data[3].toInt() and 0xFF,
                    )
                }

                PGS_WINDOW_SEGMENT -> result += PgsSegment.Window
                PGS_END_SEGMENT -> result += PgsSegment.End

                else -> return SegmentReadResult.Unavailable(
                    "unsupported-pgs-segment=0x${type.toString(16)}",
                    true,
                )
            }

            position = segmentEnd
        }

        if (position != payloadEnd) {
            return SegmentReadResult.Unavailable("too-many-pgs-segments", true)
        }
        if (result.isEmpty()) {
            return SegmentReadResult.Unavailable("empty-pgs-payload", true)
        }
        return SegmentReadResult.Ready(result)
    }

    private fun parsePcs(
        data: ByteArray,
        presentationTimeMs: Long,
        durationMs: Long?,
    ): PgsSegment.Presentation? {
        if (data.size < 11) return null
        val compositionNumber = readUInt16(data, 5)
        val state = data[7].toInt() and 0xFF
        val paletteUpdate = (data[8].toInt() and 0xFF) != 0
        val paletteId = data[9].toInt() and 0xFF
        val objectCount = data[10].toInt() and 0xFF

        var offset = 11
        val objects = ArrayList<PgsObjectRef>(objectCount)
        repeat(objectCount) {
            if (offset + 8 > data.size) return null
            val objectId = readUInt16(data, offset)
            val windowId = data[offset + 2].toInt() and 0xFF
            val flags = data[offset + 3].toInt() and 0xFF
            val x = readUInt16(data, offset + 4)
            val y = readUInt16(data, offset + 6)
            offset += 8

            if ((flags and PGS_CROPPED_FLAG) != 0) {
                return null
            }
            objects += PgsObjectRef(
                objectId = objectId,
                windowId = windowId,
                x = x,
                y = y,
            )
        }

        return PgsSegment.Presentation(
            presentationTimeMs = presentationTimeMs,
            durationMs = durationMs,
            compositionNumber = compositionNumber,
            state = state,
            paletteUpdate = paletteUpdate,
            paletteId = paletteId,
            objects = objects,
        )
    }

    private class TimelineBuilder {
        private val cues = mutableListOf<SubtitleSyncCue>()
        private val objectVersions = mutableMapOf<Int, Int>()
        private val partialObjects = mutableMapOf<Int, Int>()
        private val paletteVersions = mutableMapOf<Int, Int>()

        private var pending: PendingPresentation? = null
        private var active: ActivePresentation? = null

        fun consume(sample: PgsContainerSample): String? {
            for (segment in sample.segments) {
                when (segment) {
                    is PgsSegment.Presentation -> {
                        if (pending != null) return "pcs-before-end"
                        if (segment.state == PGS_EPOCH_START) {
                            objectVersions.clear()
                            partialObjects.clear()
                            paletteVersions.clear()
                        }
                        pending = PendingPresentation(segment)
                    }

                    is PgsSegment.Palette -> {
                        paletteVersions[segment.id] = segment.version
                    }

                    is PgsSegment.ObjectData -> {
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
                    }

                    PgsSegment.Window -> Unit

                    PgsSegment.End -> {
                        val current = pending ?: return "end-without-pcs"
                        val error = commit(current.presentation)
                        if (error != null) return error
                        pending = null
                    }
                }
            }
            return null
        }

        private fun commit(presentation: PgsSegment.Presentation): String? {
            val timeMs = presentation.presentationTimeMs
            expireActiveBefore(timeMs)

            if (presentation.objects.isEmpty()) {
                closeActive(timeMs)
                return null
            }

            val paletteVersion = paletteVersions[presentation.paletteId]
                ?: return "missing-palette id=${presentation.paletteId}"
            val objectSignatures = ArrayList<PgsObjectSignature>(presentation.objects.size)
            for (ref in presentation.objects) {
                val version = objectVersions[ref.objectId]
                    ?: return "missing-object id=${ref.objectId}"
                if (partialObjects.containsKey(ref.objectId)) {
                    return "incomplete-object id=${ref.objectId}"
                }
                objectSignatures += PgsObjectSignature(
                    objectId = ref.objectId,
                    version = version,
                    windowId = ref.windowId,
                    x = ref.x,
                    y = ref.y,
                )
            }

            val signature = PgsPresentationSignature(
                paletteId = presentation.paletteId,
                paletteVersion = paletteVersion,
                objects = objectSignatures,
            )
            val explicitEnd = presentation.durationMs
                ?.takeIf { it > 0L && timeMs <= Long.MAX_VALUE - it }
                ?.let { timeMs + it }

            val currentActive = active
            if (currentActive != null && currentActive.signature == signature) {
                if (currentActive.explicitEndMs != null && currentActive.explicitEndMs <= timeMs) {
                    closeActive(currentActive.explicitEndMs)
                    active = ActivePresentation(timeMs, explicitEnd, signature)
                } else if (explicitEnd != null) {
                    val extended = currentActive.explicitEndMs?.let { max(it, explicitEnd) } ?: explicitEnd
                    active = currentActive.copy(explicitEndMs = extended)
                }
                return null
            }

            closeActive(timeMs)
            active = ActivePresentation(
                startTimeMs = timeMs,
                explicitEndMs = explicitEnd,
                signature = signature,
            )
            return null
        }

        private fun expireActiveBefore(timeMs: Long) {
            val current = active ?: return
            val explicitEnd = current.explicitEndMs ?: return
            if (explicitEnd < timeMs) {
                closeActive(explicitEnd)
            }
        }

        private fun closeActive(endTimeMs: Long) {
            val current = active ?: return
            val end = current.explicitEndMs?.let { minOf(endTimeMs, it) } ?: endTimeMs
            if (end > current.startTimeMs) {
                cues += SubtitleSyncCue(
                    startTimeMs = current.startTimeMs,
                    endTimeMs = end,
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

            val current = active
            if (current != null) {
                val explicitEnd = current.explicitEndMs
                    ?: return TimelineResult.Unavailable("unresolved-final-presentation")
                closeActive(explicitEnd)
            }

            return TimelineResult.Ready(
                cues = cues
                    .sortedBy { it.startTimeMs }
                    .filter { it.endTimeMs > it.startTimeMs }
                    .distinctBy { it.startTimeMs to it.endTimeMs },
            )
        }
    }

    private class PagedReader(
        private val source: PgsRangeReader,
    ) {
        private val pages = object : LinkedHashMap<Long, ByteArray>(
            MAX_CACHED_PAGES,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, ByteArray>?,
            ): Boolean = size > MAX_CACHED_PAGES
        }

        suspend fun read(start: Long, length: Int): ByteArray? {
            if (start < 0L || length <= 0) return null
            val output = ByteArray(length)
            var outputOffset = 0
            var absolute = start

            while (outputOffset < length) {
                val pageStart = absolute - (absolute % PAGE_BYTES)
                val page = pages[pageStart] ?: source.read(pageStart, PAGE_BYTES)?.also {
                    pages[pageStart] = it
                } ?: return null

                val inPage = (absolute - pageStart).toInt()
                if (inPage !in 0 until page.size) return null
                val available = minOf(length - outputOffset, page.size - inPage)
                if (available <= 0) return null
                page.copyInto(
                    destination = output,
                    destinationOffset = outputOffset,
                    startIndex = inPage,
                    endIndex = inPage + available,
                )
                outputOffset += available
                absolute += available.toLong()
            }
            return output
        }

        suspend fun readElementHeader(position: Long): ElementHeader? {
            val prefix = read(position, 12) ?: return null
            val idLength = vintLength(prefix[0].toInt() and 0xFF) ?: return null
            if (idLength > 4 || idLength >= prefix.size) return null

            var id = 0L
            for (index in 0 until idLength) {
                id = (id shl 8) or (prefix[index].toLong() and 0xFFL)
            }

            val sizeOffset = idLength
            val sizeLength = vintLength(prefix[sizeOffset].toInt() and 0xFF) ?: return null
            if (sizeLength > 8 || sizeOffset + sizeLength > prefix.size) return null
            val markerMask = 1 shl (8 - sizeLength)
            var sizeValue = (prefix[sizeOffset].toInt() and (markerMask - 1)).toLong()
            for (index in 1 until sizeLength) {
                sizeValue = (sizeValue shl 8) or (prefix[sizeOffset + index].toLong() and 0xFFL)
            }
            val unknownValue = (1L shl (7 * sizeLength)) - 1L
            val size = sizeValue.takeUnless { it == unknownValue }
            return ElementHeader(
                id = id,
                size = size,
                dataStart = position + idLength + sizeLength,
            )
        }

        suspend fun readUnsigned(position: Long, size: Long?): Long? {
            val length = size?.takeIf { it in 1L..8L }?.toInt() ?: return null
            val bytes = read(position, length) ?: return null
            var value = 0L
            for (byte in bytes) {
                value = (value shl 8) or (byte.toLong() and 0xFFL)
            }
            return value
        }
    }

    private fun readVintValue(bytes: ByteArray, offset: Int): VintValue? {
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

    private data class LocatorIdentity(
        val clusterPosition: Long,
        val relativePosition: Long?,
        val blockNumber: Long?,
        val cueTimeTicks: Long,
    )

    private data class ClusterInfo(
        val start: Long,
        val dataStart: Long,
        val end: Long?,
        val timestampTicks: Long,
    )

    private data class ElementHeader(
        val id: Long,
        val size: Long?,
        val dataStart: Long,
    )

    private data class VintValue(
        val value: Long,
        val length: Int,
    )

    private data class PgsContainerSample(
        val presentationTimeMs: Long,
        val durationMs: Long?,
        val segments: List<PgsSegment>,
    )

    private sealed interface SampleResult {
        data class Ready(val sample: PgsContainerSample) : SampleResult
        data class Unavailable(val reason: String, val cacheable: Boolean) : SampleResult
    }

    private sealed interface SegmentReadResult {
        data class Ready(val segments: List<PgsSegment>) : SegmentReadResult
        data class Unavailable(val reason: String, val cacheable: Boolean) : SegmentReadResult
    }

    private sealed interface TimelineResult {
        data class Ready(val cues: List<SubtitleSyncCue>) : TimelineResult
        data class Unavailable(val reason: String) : TimelineResult
    }

    private sealed interface PgsSegment {
        data class Presentation(
            val presentationTimeMs: Long,
            val durationMs: Long?,
            val compositionNumber: Int,
            val state: Int,
            val paletteUpdate: Boolean,
            val paletteId: Int,
            val objects: List<PgsObjectRef>,
        ) : PgsSegment

        data class Palette(
            val id: Int,
            val version: Int,
        ) : PgsSegment

        data class ObjectData(
            val id: Int,
            val version: Int,
            val sequence: Int,
        ) : PgsSegment

        data object Window : PgsSegment
        data object End : PgsSegment
    }

    private data class PgsObjectRef(
        val objectId: Int,
        val windowId: Int,
        val x: Int,
        val y: Int,
    )

    private data class PendingPresentation(
        val presentation: PgsSegment.Presentation,
    )

    private data class PgsObjectSignature(
        val objectId: Int,
        val version: Int,
        val windowId: Int,
        val x: Int,
        val y: Int,
    )

    private data class PgsPresentationSignature(
        val paletteId: Int,
        val paletteVersion: Int,
        val objects: List<PgsObjectSignature>,
    )

    private data class ActivePresentation(
        val startTimeMs: Long,
        val explicitEndMs: Long?,
        val signature: PgsPresentationSignature,
    )
}
