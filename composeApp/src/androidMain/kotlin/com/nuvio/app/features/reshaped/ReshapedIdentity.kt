package com.nuvio.app.features.reshaped

import android.content.Context
import android.content.pm.PackageManager

/**
 * Nuvio RS ships under its own application id so it installs next to official Nuvio. Builds of
 * the fork made before the rename used the official id, [LEGACY_PACKAGE]; a final "bridge" build
 * under that id hands its data to Nuvio RS (see [ReshapedMigrationProvider]).
 */
internal object ReshapedIdentity {
    const val LEGACY_PACKAGE = "com.nuvio.app"
    const val RESHAPED_PACKAGE = "com.nuvioreshaped.app"
    const val MIGRATION_AUTHORITY_SUFFIX = ".reshaped.migration"
    const val MIGRATION_PATH = "data.zip"

    fun isLegacyBuild(context: Context): Boolean = context.packageName == LEGACY_PACKAGE

    /** Release builds of Nuvio RS; debug builds use their own id and never migrate. */
    fun isReshapedBuild(context: Context): Boolean = context.packageName == RESHAPED_PACKAGE

    /** True when [packageName] is installed and signed with this app's key, i.e. it is ours. */
    fun isOurInstalledPackage(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess
        return installed && pm.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH
    }

    fun migrationAuthority(packageName: String): String = packageName + MIGRATION_AUTHORITY_SUFFIX
}
