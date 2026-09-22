package com.nuvio.app.features.autosync

internal enum class PgsIndexedCueMode {
    KEEP_RAW,
    ALTERNATING,
    AMBIGUOUS,
    UNAVAILABLE,
}

internal data class PgsCueProbeSample(
    val cueIndex: Int,
    val bytes: ByteArray,
)

internal data class PgsCueProbeResult(
    val mode: PgsIndexedCueMode,
    val visibleParity: Int? = null,
    val visibleCount: Int = 0,
    val clearCount: Int = 0,
    val parsedCount: Int = 0,
)

internal object PgsCueSemanticParser {
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_SIMPLE_BLOCK = 0xA3L

    private const val PGS_PRESENTATION_SEGMENT = 0x16
    private const val PGS_PALETTE_SEGMENT = 0x14
    private const val PGS_OBJECT_SEGMENT = 0x15
    private const val PGS_WINDOW_SEGMENT = 0x17
    private const val PGS_DISPLAY_SEGMENT = 0x80

    private const val MAX_ELEMENT_SCAN_OFFSET = 32
    private const val MIN_PARSED_SAMPLES = 4

    fun classify(
        samples: List<PgsCueProbeSample>,
        trackNumber: Int,
    ): PgsCueProbeResult {
        if (samples.size < MIN_PARSED_SAMPLES) {
            return PgsCueProbeResult(PgsIndexedCueMode.UNAVAILABLE)
        }

        val parsed = samples.mapNotNull { sample ->
            parsePresentationState(sample.bytes, trackNumber)?.let { state ->
                sample.cueIndex to state
            }
        }
        val minimumRequired = maxOf(MIN_PARSED_SAMPLES, (samples.size * 3 + 3) / 4)
        if (parsed.size < minimumRequired) {
            return PgsCueProbeResult(
                mode = PgsIndexedCueMode.UNAVAILABLE,
                parsedCount = parsed.size,
            )
        }

        if (parsed.any { it.second == PresentationState.NON_PRESENTATION }) {
            return counts(
                mode = PgsIndexedCueMode.AMBIGUOUS,
                parsed = parsed,
            )
        }

        val visible = parsed.count { it.second == PresentationState.VISIBLE }
        val clear = parsed.count { it.second == PresentationState.CLEAR }
        if (visible == parsed.size) {
            return PgsCueProbeResult(
                mode = PgsIndexedCueMode.KEEP_RAW,
                visibleCount = visible,
                parsedCount = parsed.size,
            )
        }
        if (visible == 0 || clear == 0) {
            return counts(
                mode = PgsIndexedCueMode.AMBIGUOUS,
                parsed = parsed,
            )
        }

        var visibleParity: Int? = null
        for ((cueIndex, state) in parsed) {
            val candidateParity = when (state) {
                PresentationState.VISIBLE -> cueIndex and 1
                PresentationState.CLEAR -> (cueIndex + 1) and 1
                PresentationState.NON_PRESENTATION -> return counts(
                    mode = PgsIndexedCueMode.AMBIGUOUS,
                    parsed = parsed,
                )
            }
            if (visibleParity == null) {
                visibleParity = candidateParity
            } else if (visibleParity != candidateParity) {
                return counts(
                    mode = PgsIndexedCueMode.AMBIGUOUS,
                    parsed = parsed,
                )
            }
        }

        return PgsCueProbeResult(
            mode = PgsIndexedCueMode.ALTERNATING,
            visibleParity = visibleParity,
            visibleCount = visible,
            clearCount = clear,
            parsedCount = parsed.size,
        )
    }

    private fun counts(
        mode: PgsIndexedCueMode,
        parsed: List<Pair<Int, PresentationState>>,
    ): PgsCueProbeResult = PgsCueProbeResult(
        mode = mode,
        visibleCount = parsed.count { it.second == PresentationState.VISIBLE },
        clearCount = parsed.count { it.second == PresentationState.CLEAR },
        parsedCount = parsed.size,
    )

    private fun parsePresentationState(
        bytes: ByteArray,
        expectedTrackNumber: Int,
    ): PresentationState? {
        val maxOffset = minOf(MAX_ELEMENT_SCAN_OFFSET, bytes.lastIndex)
        for (offset in 0..maxOffset) {
            val element = readElementHeader(bytes, offset) ?: continue
            when (element.id) {
                ID_SIMPLE_BLOCK -> {
                    parseBlock(bytes, element.dataStart, expectedTrackNumber)?.let { return it }
                }

                ID_BLOCK_GROUP -> {
                    parseBlockGroup(bytes, element, expectedTrackNumber)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseBlockGroup(
        bytes: ByteArray,
        group: ElementHeader,
        expectedTrackNumber: Int,
    ): PresentationState? {
        val declaredEnd = group.size
            ?.takeIf { it <= Int.MAX_VALUE.toLong() }
            ?.let { size -> (group.dataStart.toLong() + size).coerceAtMost(bytes.size.toLong()).toInt() }
            ?: bytes.size
        var position = group.dataStart
        var childCount = 0
        while (position < declaredEnd && childCount++ < 16) {
            val child = readElementHeader(bytes, position) ?: return null
            if (child.id == ID_BLOCK) {
                return parseBlock(bytes, child.dataStart, expectedTrackNumber)
            }
            val size = child.size ?: return null
            val next = child.dataStart.toLong() + size
            if (next <= position.toLong() || next > declaredEnd.toLong()) return null
            position = next.toInt()
        }
        return null
    }

    private fun parseBlock(
        bytes: ByteArray,
        dataStart: Int,
        expectedTrackNumber: Int,
    ): PresentationState? {
        val track = readVintValue(bytes, dataStart) ?: return null
        if (track.value != expectedTrackNumber.toLong()) return null

        val flagsIndex = dataStart + track.length + 2
        if (flagsIndex >= bytes.size) return null
        val flags = bytes[flagsIndex].toInt() and 0xFF
        if ((flags and 0x06) != 0) return null

        val payloadStart = flagsIndex + 1
        if (payloadStart >= bytes.size) return null
        return parsePgsPayload(bytes, payloadStart)
    }

    private fun parsePgsPayload(
        bytes: ByteArray,
        start: Int,
    ): PresentationState? {
        var position = start
        var sawKnownSegment = false

        while (position + 3 <= bytes.size) {
            val hasSupHeader =
                position + 13 <= bytes.size &&
                    bytes[position].toInt() == 0x50 &&
                    bytes[position + 1].toInt() == 0x47

            val typeOffset = if (hasSupHeader) position + 10 else position
            val lengthOffset = typeOffset + 1
            if (lengthOffset + 1 >= bytes.size) break

            val type = bytes[typeOffset].toInt() and 0xFF
            val length =
                ((bytes[lengthOffset].toInt() and 0xFF) shl 8) or
                    (bytes[lengthOffset + 1].toInt() and 0xFF)
            val dataStart = lengthOffset + 2

            if (type !in setOf(
                    PGS_PALETTE_SEGMENT,
                    PGS_OBJECT_SEGMENT,
                    PGS_PRESENTATION_SEGMENT,
                    PGS_WINDOW_SEGMENT,
                    PGS_DISPLAY_SEGMENT,
                )
            ) {
                return if (sawKnownSegment) PresentationState.NON_PRESENTATION else null
            }
            sawKnownSegment = true

            if (type == PGS_PRESENTATION_SEGMENT) {
                if (length < 11 || dataStart + 10 >= bytes.size) return null
                val objectCount = bytes[dataStart + 10].toInt() and 0xFF
                return if (objectCount == 0) {
                    PresentationState.CLEAR
                } else {
                    PresentationState.VISIBLE
                }
            }

            val next = dataStart.toLong() + length.toLong()
            if (next <= position.toLong()) return null
            if (next > bytes.size.toLong()) {
                return PresentationState.NON_PRESENTATION
            }
            position = next.toInt()
        }

        return if (sawKnownSegment) PresentationState.NON_PRESENTATION else null
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
            dataStart = sizeOffset + sizeLength,
        )
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

    private enum class PresentationState {
        VISIBLE,
        CLEAR,
        NON_PRESENTATION,
    }

    private data class ElementHeader(
        val id: Long,
        val size: Long?,
        val dataStart: Int,
    )

    private data class VintValue(
        val value: Long,
        val length: Int,
    )
}
