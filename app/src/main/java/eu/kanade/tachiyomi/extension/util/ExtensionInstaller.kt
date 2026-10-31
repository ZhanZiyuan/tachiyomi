package eu.kanade.tachiyomi.extension.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.core.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(private val context: Context) {

    /**
     * The system's download manager
     */
    private val downloadManager = context.getSystemService<DownloadManager>()!!

    /**
     * The broadcast receiver which listens to download completion events.
     */
    private val downloadReceiver = DownloadCompletionReceiver()

    /**
     * The currently requested downloads, with the package name (unique id) as key, and the id
     * returned by the download manager.
     */
    private val activeDownloads = ConcurrentHashMap<String, Long>()

    private val downloadsStateFlows = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()

    private val expectedDownloads = ConcurrentHashMap<Long, Extension.Available>()
    private val verificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val verificationJobs = ConcurrentHashMap<Long, Job>()
    private val verifiedFiles = ConcurrentHashMap<Long, File>()

    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: Extension.Available): Flow<InstallStep> {
        val apkUrl = url.toHttpUrlOrNull()
        if (apkUrl == null || !apkUrl.isHttps || apkUrl.username.isNotEmpty() || apkUrl.password.isNotEmpty()) {
            return flowOf(InstallStep.Error)
        }
        val pkgName = extension.pkgName

        val oldDownload = activeDownloads[pkgName]
        if (oldDownload != null) {
            deleteDownload(pkgName)
        }

        // Register the receiver after removing (and unregistering) the previous download
        downloadReceiver.register()

        val downloadUri = url.toUri()
        val request = DownloadManager.Request(downloadUri)
            .setTitle(extension.name)
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadUri.lastPathSegment)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = downloadManager.enqueue(request)
        activeDownloads[pkgName] = id
        expectedDownloads[id] = extension

        val downloadStateFlow = MutableStateFlow(InstallStep.Pending)
        downloadsStateFlows[id] = downloadStateFlow

        // Poll download status
        val pollStatusFlow = downloadStatusFlow(id).mapNotNull { downloadStatus ->
            // Map to our model
            when (downloadStatus) {
                DownloadManager.STATUS_PENDING -> InstallStep.Pending
                DownloadManager.STATUS_RUNNING -> InstallStep.Downloading
                DownloadManager.STATUS_FAILED -> InstallStep.Error
                else -> null
            }
        }

        return merge(downloadStateFlow, pollStatusFlow).transformWhile {
            emit(it)
            // Stop when the application is installed or errors
            !it.isCompleted()
        }.onCompletion {
            // Always notify on main thread
            withUIContext {
                // Always remove the download when unsubscribed
                if (activeDownloads[pkgName] == id) deleteDownload(pkgName)
            }
        }
    }

    /**
     * Returns a flow that polls the given download id for its status every second, as the
     * manager doesn't have any notification system. It'll stop once the download finishes.
     *
     * @param id The id of the download to poll.
     */
    private fun downloadStatusFlow(id: Long): Flow<Int> = flow {
        val query = DownloadManager.Query().setFilterById(id)
        while (true) {
            // Get the current download status
            val downloadStatus = downloadManager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return@flow
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }

            emit(downloadStatus)

            // Stop polling when the download fails or finishes
            if (
                downloadStatus == DownloadManager.STATUS_SUCCESSFUL ||
                downloadStatus == DownloadManager.STATUS_FAILED
            ) {
                return@flow
            }

            delay(1.seconds)
        }
    }
        // Ignore duplicate results
        .distinctUntilChanged()

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param apkFile The private, verified copy of the extension to install.
     */
    private fun installApk(downloadId: Long, apkFile: File) {
        val uri = apkFile.getUriCompat(context)
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(uri, APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                val extensionManager = Injekt.get<ExtensionManager>()
                try {
                    if (ExtensionLoader.installPrivateExtensionFile(context, apkFile)) {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Installed)
                    } else {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                    extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                }
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(context, downloadId, uri, installer)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        val downloadId = activeDownloads[pkgName] ?: return
        updateInstallStep(downloadId, InstallStep.Idle)
        Installer.cancelInstallQueue(context, downloadId)
        deleteDownload(pkgName)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        downloadsStateFlows[downloadId]?.let { it.value = step }
    }

    /**
     * Deletes the download for the given package name.
     *
     * @param pkgName The package name of the download to delete.
     */
    private fun deleteDownload(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName)
        if (downloadId != null) {
            downloadManager.remove(downloadId)
            downloadsStateFlows.remove(downloadId)
            expectedDownloads.remove(downloadId)
            verificationJobs.remove(downloadId)?.cancel()
            verifiedFiles.remove(downloadId)?.delete()
        }
        if (activeDownloads.isEmpty()) {
            downloadReceiver.unregister()
        }
    }

    /** Verify a private copy, so the installed bytes are the bytes we inspected. */
    private fun verifyDownloadedApk(id: Long, uri: Uri, expected: Extension.Available): File {
        val apkFile = File(context.cacheDir, "extension_verified_$id.apk")
        try {
            if (apkFile.exists() && !apkFile.delete()) throw IOException("Cannot replace temporary extension")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot read downloaded extension")
            input.use {
                apkFile.outputStream().use { output ->
                    check(apkFile.setReadOnly()) { "Cannot protect temporary extension" }
                    it.copyTo(output)
                }
            }
            val packageInfo = ExtensionLoader.getArchivePackageInfo(context, apkFile)
                ?: throw IOException("Downloaded file is not an extension APK")
            val signatures = ExtensionLoader.getSignatures(packageInfo).orEmpty()
            check(
                matchesExtensionListing(
                    expectedPackage = expected.pkgName,
                    expectedVersion = expected.versionCode,
                    expectedSigningKey = expected.signingKey,
                    actualPackage = packageInfo.packageName,
                    actualVersion = PackageInfoCompat.getLongVersionCode(packageInfo),
                    actualSigningKeys = signatures,
                ),
            ) {
                "Downloaded extension does not match its catalog: " +
                    "package=${packageInfo.packageName} (expected ${expected.pkgName}), " +
                    "version=${PackageInfoCompat.getLongVersionCode(packageInfo)} (expected ${expected.versionCode}), " +
                    "signers=$signatures (expected ${expected.signingKey})"
            }
            return apkFile
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        }
    }

    /**
     * Receiver that listens to download status events.
     */
    private inner class DownloadCompletionReceiver : BroadcastReceiver() {

        /**
         * Whether this receiver is currently registered.
         */
        private var isRegistered = false

        /**
         * Registers this receiver if it's not already.
         */
        fun register() {
            if (isRegistered) return
            isRegistered = true

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        /**
         * Unregisters this receiver if it's not already.
         */
        fun unregister() {
            if (!isRegistered) return
            isRegistered = false

            context.unregisterReceiver(this)
        }

        /**
         * Called when a download event is received. It looks for the download in the current active
         * downloads and notifies its installation step.
         */
        override fun onReceive(context: Context, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0) ?: return

            // Avoid events for downloads we didn't request
            if (id !in activeDownloads.values) return

            val expected = expectedDownloads[id] ?: return
            if (verificationJobs[id]?.isActive == true || verifiedFiles.containsKey(id)) return
            val query = DownloadManager.Query().setFilterById(id)
            val status = downloadManager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                if (status == DownloadManager.STATUS_FAILED) updateInstallStep(id, InstallStep.Error)
                return
            }
            val uri = downloadManager.getUriForDownloadedFile(id)
            if (uri == null) {
                updateInstallStep(id, InstallStep.Error)
                return
            }

            val pendingResult = goAsync()
            verificationJobs[id] = verificationScope.launch {
                var apkFile: File? = null
                try {
                    val verified = withIOContext { verifyDownloadedApk(id, uri, expected) }
                    apkFile = verified
                    if (activeDownloads[expected.pkgName] != id) return@launch
                    verifiedFiles[id] = verified
                    installApk(id, verified)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to verify downloaded extension" }
                    context.toast(MR.strings.ext_verification_error)
                    updateInstallStep(id, InstallStep.Error)
                } finally {
                    if (!verifiedFiles.containsKey(id)) {
                        apkFile?.delete()
                        // withIOContext can be cancelled after the copy completes but before returning it.
                        File(context.cacheDir, "extension_verified_$id.apk").delete()
                    }
                    verificationJobs.remove(id)
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
    }
}
