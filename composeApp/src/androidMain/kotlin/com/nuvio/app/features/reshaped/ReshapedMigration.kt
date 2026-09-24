package com.nuvio.app.features.reshaped

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * One-time import of the pre-rename fork's settings into Nuvio RS. Runs from
 * NuvioApplication.attachBaseContext, before any provider or screen reads storage, so imported
 * settings are simply what the app finds when it starts.
 */
internal object ReshapedMigration {
    const val STATE_NONE = "none"
    const val STATE_IMPORTED = "imported"
    const val STATE_PENDING = "pending"
    /** Import finished or skipped by the user; nothing left to ask. */
    const val STATE_DONE = "done"

    private const val TAG = "NuvioRsMigration"

    fun importIfNeeded(context: Context) {
        if (!ReshapedIdentity.isReshapedBuild(context)) return
        val current = state(context)
        if (current == STATE_IMPORTED || current == STATE_NONE || current == STATE_DONE) return
        if (!ReshapedIdentity.isOurInstalledPackage(context, ReshapedIdentity.LEGACY_PACKAGE)) {
            if (current == null) setState(context, STATE_NONE)
            return
        }
        val uri = Uri.parse(
            "content://${ReshapedIdentity.migrationAuthority(ReshapedIdentity.LEGACY_PACKAGE)}/" +
                ReshapedIdentity.MIGRATION_PATH,
        )
        val imported = runCatching {
            context.contentResolver.openInputStream(uri)?.use { importZip(it, context) } ?: false
        }.onFailure {
            // Old versions without the export provider land here; the UI asks the user to update it.
            Log.i(TAG, "legacy export unavailable: ${it.message}")
        }.getOrDefault(false)
        setState(context, if (imported) STATE_IMPORTED else STATE_PENDING)
    }

    /** True once the installed legacy app is a bridge build that can export its data. */
    fun legacyExportAvailable(context: Context): Boolean =
        ReshapedIdentity.isOurInstalledPackage(context, ReshapedIdentity.LEGACY_PACKAGE) &&
            context.packageManager.resolveContentProvider(
                ReshapedIdentity.migrationAuthority(ReshapedIdentity.LEGACY_PACKAGE),
                0,
            ) != null

    fun state(context: Context): String? =
        runCatching { markerFile(context).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    fun setState(context: Context, state: String) {
        runCatching { markerFile(context).writeText(state) }
    }

    /** Extracts into a staging directory first and only applies a complete export. */
    private fun importZip(input: InputStream, context: Context): Boolean {
        val dataDir = context.dataDir
        val staging = File(context.noBackupFilesDir, "nuvio-rs-import").apply { deleteRecursively(); mkdirs() }
        var complete = false
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == ReshapedMigrationFiles.COMPLETE_MARKER) {
                        complete = true
                        continue
                    }
                    if (entry.isDirectory) continue
                    val target = File(staging, entry.name)
                    // Reject anything outside the staged data roots (zip-slip and unexpected paths).
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                    if (!ReshapedMigrationFiles.isAllowed(staging, target)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            if (!complete) return false
            staging.walkTopDown().filter { it.isFile }.forEach { file ->
                val destination = File(dataDir, file.relativeTo(staging).path)
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun markerFile(context: Context) = File(context.noBackupFilesDir, "nuvio_rs_migration")
}
