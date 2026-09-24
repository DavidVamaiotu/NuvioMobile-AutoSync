package com.nuvio.app.features.reshaped

import com.nuvio.app.features.updater.AppUpdaterRepository
import com.nuvio.app.features.updater.UpdateChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReshapedApkAssetsTest {
    private val tag = "0.5.1-beta-autosync.7"
    private val releaseAssets = listOf(
        "NuvioAutoSync-$tag-bridge-universal.apk",
        "NuvioRS-$tag-arm64.apk",
        "NuvioRS-$tag-arm32.apk",
        "NuvioRS-$tag-x64.apk",
        "NuvioRS-$tag-x32.apk",
    )

    @Test
    fun picksTheNuvioRsApkForTheDeviceAbi() {
        assertEquals("NuvioRS-$tag-arm64.apk", ReshapedApkAssets.choose(releaseAssets, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("NuvioRS-$tag-arm32.apk", ReshapedApkAssets.choose(releaseAssets, listOf("armeabi-v7a")))
        assertEquals("NuvioRS-$tag-x64.apk", ReshapedApkAssets.choose(releaseAssets, listOf("x86_64", "x86")))
        assertEquals("NuvioRS-$tag-x32.apk", ReshapedApkAssets.choose(releaseAssets, listOf("x86")))
    }

    @Test
    fun ignoresReleasesWithoutNuvioRsApks() {
        assertNull(ReshapedApkAssets.choose(listOf("NuvioAutoSync-1.0-arm64-v8a.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun updaterNeverPicksTheBridge() {
        val response = """[{"tag_name": "$tag", "assets": [${
            releaseAssets.joinToString { """{"name": "$it", "browser_download_url": "https://example.com/$it"}""" }
        }]}]"""
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abi ->
            val asset = AppUpdaterRepository.selectUpdate(response, UpdateChannel.BETA, listOf(abi))?.assetName
            assertEquals(true, asset?.startsWith(ReshapedApkAssets.PREFIX), "$abi picked $asset")
        }
    }

    /** The asset selection of builds released before the rename, which must land on the bridge. */
    @Test
    fun preRenameUpdatersPickTheBridge() {
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abi ->
            assertEquals("NuvioAutoSync-$tag-bridge-universal.apk", preRenameChoice(releaseAssets, listOf(abi)))
        }
    }

    private fun preRenameChoice(names: List<String>, supportedAbis: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        for (abi in supportedAbis) {
            apks.firstOrNull { it.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { val name = it.lowercase(); name.contains("universal") || name.contains("all") }
            ?: apks.firstOrNull()
    }
}
