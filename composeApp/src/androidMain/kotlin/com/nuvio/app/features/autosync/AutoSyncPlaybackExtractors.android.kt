package com.nuvio.app.features.autosync

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
