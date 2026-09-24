package com.nuvio.app.features.reshaped

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams this app's settings as a zip so a newer build under a different application id can
 * import them (see [ReshapedMigration]). Protected by a signature permission and an explicit
 * signature check: only apps signed with the same key can read it.
 */
class ReshapedMigrationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        if (mode != "r" || uri.lastPathSegment != ReshapedIdentity.MIGRATION_PATH) throw FileNotFoundException()
        val callerUid = Binder.getCallingUid()
        if (context.packageManager.checkSignatures(callerUid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Caller is not signed with this app's key")
        }
        val dataDir = context.dataDir
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    ZipOutputStream(output).use { zip ->
                        ReshapedMigrationFiles.exportable(dataDir).forEach { file ->
                            zip.putNextEntry(ZipEntry(file.relativeTo(dataDir).invariantSeparatorsPath))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                        // Written last: the importer only applies an export that reached this entry.
                        zip.putNextEntry(ZipEntry(ReshapedMigrationFiles.COMPLETE_MARKER))
                        zip.closeEntry()
                    }
                }
            }.onFailure { Log.w(TAG, "export failed", it) }
        }, "nuvio-rs-export").start()
        return read
    }

    override fun getType(uri: Uri): String = "application/zip"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "NuvioRsMigration"
    }
}

/** Which parts of the data directory move between builds. */
internal object ReshapedMigrationFiles {
    private val roots = listOf("shared_prefs", "databases", "files")
    private val excludedPaths = setOf(
        // Encrypted with Android Keystore keys that stay with the old app; the user signs in again.
        "shared_prefs/nuvio_mdblist_auth.xml",
        "shared_prefs/nuvio_simkl_auth.xml",
        // Offline downloads are large; they stay with the old app.
        "files/downloads",
        "files/download-transfers",
    )
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val COMPLETE_MARKER = "nuvio-rs-export-complete"

    fun exportable(dataDir: File): Sequence<File> = roots.asSequence()
        .map { File(dataDir, it) }
        .filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().onEnter { dir -> isAllowed(dataDir, dir) } }
        .filter { it.isFile && it.length() <= MAX_FILE_BYTES && isAllowed(dataDir, it) }

    fun isAllowed(dataDir: File, file: File): Boolean {
        val relative = file.relativeTo(dataDir).invariantSeparatorsPath
        if (roots.none { relative == it || relative.startsWith("$it/") }) return false
        if (relative.startsWith("databases/androidx.work")) return false
        return excludedPaths.none { relative == it || relative.startsWith("$it/") }
    }
}
