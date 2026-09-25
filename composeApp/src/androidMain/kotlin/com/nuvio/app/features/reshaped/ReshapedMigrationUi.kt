package com.nuvio.app.features.reshaped

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.nuvio.app.R
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.AppUpdaterRepository
import com.nuvio.app.features.updater.UpdateChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The prompts that move people from the pre-rename fork to Nuvio RS.
 *
 * Old build (bridge): install Nuvio RS, then uninstall this app.
 * Nuvio RS: after importing, offer to uninstall the old app; if the old app is too old to
 * export, ask the user to update it first, then restart to import.
 */
internal class ReshapedMigrationUi(private val activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dialog: AlertDialog? = null
    private var download: Job? = null
    private var dismissedThisProcess = false

    fun onResume() {
        if (!AppUpdaterPlatform.isSupported || dismissedThisProcess) return
        if (dialog?.isShowing == true || download?.isActive == true) return
        when {
            ReshapedIdentity.isLegacyBuild(activity) -> showBridgePrompt()
            ReshapedIdentity.isReshapedBuild(activity) -> showReshapedPrompt()
        }
    }

    fun onDestroy() {
        dialog?.dismiss()
        scope.cancel()
    }

    private fun showBridgePrompt() {
        if (ReshapedIdentity.isOurInstalledPackage(activity, ReshapedIdentity.RESHAPED_PACKAGE)) {
            show(
                title = R.string.nuvio_rs_moved_title,
                message = R.string.nuvio_rs_bridge_installed_message,
                positive = R.string.nuvio_rs_open to ::openReshaped,
                neutral = R.string.nuvio_rs_uninstall_old to { uninstall(activity.packageName) },
            )
        } else {
            show(
                title = R.string.nuvio_rs_moved_title,
                message = R.string.nuvio_rs_bridge_install_message,
                positive = R.string.nuvio_rs_install to ::installReshaped,
            )
        }
    }

    private fun showReshapedPrompt() {
        if (!ReshapedIdentity.isOurInstalledPackage(activity, ReshapedIdentity.LEGACY_PACKAGE)) return
        when (ReshapedMigration.state(activity)) {
            ReshapedMigration.STATE_IMPORTED -> show(
                title = R.string.nuvio_rs_imported_title,
                message = R.string.nuvio_rs_imported_message,
                positive = R.string.nuvio_rs_uninstall_old to {
                    ReshapedMigration.setState(activity, ReshapedMigration.STATE_DONE)
                    uninstall(ReshapedIdentity.LEGACY_PACKAGE)
                },
                neutral = R.string.nuvio_rs_keep to {
                    ReshapedMigration.setState(activity, ReshapedMigration.STATE_DONE)
                },
            )
            ReshapedMigration.STATE_PENDING -> if (ReshapedMigration.legacyExportAvailable(activity)) {
                // Settings are imported only at process start, before anything reads them.
                show(
                    title = R.string.nuvio_rs_pending_title,
                    message = R.string.nuvio_rs_ready_message,
                    positive = R.string.nuvio_rs_restart to ::restart,
                )
            } else {
                show(
                    title = R.string.nuvio_rs_pending_title,
                    message = R.string.nuvio_rs_pending_message,
                    positive = R.string.nuvio_rs_open_old to {
                        launch(ReshapedIdentity.LEGACY_PACKAGE)
                    },
                    neutral = R.string.nuvio_rs_start_fresh to {
                        ReshapedMigration.setState(activity, ReshapedMigration.STATE_DONE)
                    },
                )
            }
        }
    }

    private fun show(
        title: Int,
        message: Int,
        positive: Pair<Int, () -> Unit>,
        neutral: Pair<Int, () -> Unit>? = null,
    ) {
        if (activity.isFinishing) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive.first) { _, _ -> positive.second() }
            .apply { neutral?.let { (label, action) -> setNeutralButton(label) { _, _ -> action() } } }
            .setNegativeButton(R.string.nuvio_rs_later) { _, _ -> dismissedThisProcess = true }
            .setCancelable(false)
            .show()
    }

    private fun installReshaped() {
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            toast(activity.getString(R.string.nuvio_rs_allow_installs))
            AppUpdaterPlatform.openUnknownSourcesSettings()
            return
        }
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.nuvio_rs_install)
            .setMessage(activity.getString(R.string.nuvio_rs_downloading, 0))
            .setCancelable(false)
            .show()
        dialog = progress
        download = scope.launch {
            val apk = AppUpdaterRepository.getLatestChannelUpdate(UpdateChannel.BETA)
                .mapCatching { update ->
                    check(update.assetName.startsWith(ReshapedApkAssets.PREFIX)) { "No Nuvio RS asset" }
                    AppUpdaterPlatform.downloadApk(update.assetUrl, update.assetName) { done, total ->
                        val percent = if (total != null && total > 0) (done * 100 / total).toInt() else 0
                        scope.launch { progress.setMessage(activity.getString(R.string.nuvio_rs_downloading, percent)) }
                    }.getOrThrow()
                }
            progress.dismiss()
            apk.mapCatching { path -> withContext(Dispatchers.Main) { AppUpdaterPlatform.installDownloadedApk(path).getOrThrow() } }
                .onFailure { toast(activity.getString(R.string.nuvio_rs_download_failed)) }
        }
    }

    private fun openReshaped() = launch(ReshapedIdentity.RESHAPED_PACKAGE)

    private fun launch(packageName: String) {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return
        activity.startActivity(intent)
    }

    /**
     * Opens the app's system settings page, where the user taps Uninstall. Uninstalling directly
     * would need REQUEST_DELETE_PACKAGES, which Play Protect treats as a warning sign.
     */
    private fun uninstall(packageName: String) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun restart() {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(Intent.makeRestartActivityTask(launchIntent.component))
        Runtime.getRuntime().exit(0)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
