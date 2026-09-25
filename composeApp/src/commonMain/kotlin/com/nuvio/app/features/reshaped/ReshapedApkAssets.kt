package com.nuvio.app.features.reshaped

/**
 * Release APK naming since the rename. Nuvio RS APKs are named `NuvioRS-[TV-]<tag>-<alias>.apk`, where
 * the alias deliberately avoids the ABI names and "universal"/"all" that pre-rename updaters look
 * for, so those updaters pick the legacy bridge APK published next to them instead.
 */
internal object ReshapedApkAssets {
    const val PREFIX = "NuvioRS-"

    private val abiAliases = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm32",
        "x86_64" to "x64",
        "x86" to "x32",
    )

    /** Picks the Nuvio RS APK for [supportedAbis] (else the "-any" one), or null when the release has none. */
    fun choose(apkNames: List<String>, supportedAbis: List<String>): String? {
        val reshaped = apkNames.filter { it.startsWith(PREFIX, ignoreCase = true) }
        if (reshaped.isEmpty()) return null
        for (abi in supportedAbis) {
            val alias = abiAliases[abi] ?: continue
            reshaped.firstOrNull { it.lowercase().removeSuffix(".apk").endsWith("-$alias") }?.let { return it }
        }
        return reshaped.firstOrNull { it.lowercase().removeSuffix(".apk").endsWith("-any") } ?: reshaped.first()
    }
}
