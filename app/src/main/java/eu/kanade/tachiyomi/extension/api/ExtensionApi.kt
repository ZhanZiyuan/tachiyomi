package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.preference.Preference
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val sourcePreferences: SourcePreferences by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            sourcePreferences.extensionRepos().get()
                .flatMap { getExtensions(it) }
                .groupBy { it.pkgName }
                .values
                .map { versions ->
                    versions.maxWith(compareBy<Extension.Available> { it.libVersion }.thenBy { it.versionCode })
                }
        }
    }

    private suspend fun getExtensions(repoUrl: String): List<Extension.Available> {
        return try {
            val indexUrl = ExtensionRepoUrl.indexUrl(repoUrl)
            val bytes = fetchCatalog(indexUrl)
            val supportedVersions = ExtensionLoader.LIB_VERSION_MIN..ExtensionLoader.LIB_VERSION_MAX
            if (indexUrl.endsWith("/index.pb")) {
                val store = ExtensionCatalog.decodeStore(bytes)
                val list = store.extensionListUrl?.let {
                    ExtensionCatalog.decodeList(fetchCatalog(it))
                } ?: requireNotNull(store.extensionList)
                ExtensionCatalog.toExtensions(store, list, repoUrl, supportedVersions)
            } else {
                ExtensionCatalog.legacyToExtensions(
                    ExtensionCatalog.decodeLegacy(bytes, json),
                    indexUrl.removeSuffix("/index.min.json"),
                    supportedVersions,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoUrl" }
            emptyList()
        }
    }

    private suspend fun fetchCatalog(url: String): ByteArray {
        require(ExtensionCatalog.isSecureUrl(url)) { "Insecure extension catalog URL" }
        // HTTPS redirects are allowed (GitHub uses them), but never HTTPS-to-HTTP redirects.
        return networkService.client.newBuilder().followSslRedirects(false).build()
            .newCall(GET(url)).awaitSuccess().use { response ->
                require(response.request.url.isHttps) { "Insecure extension catalog redirect" }
                val body = response.body
                require(body.contentLength() <= ExtensionCatalog.MAX_CATALOG_BYTES) {
                    "Extension catalog exceeds size limit"
                }
                body.byteStream().use { ExtensionCatalog.readBounded(it) }
            }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    fun getApkUrl(extension: Extension.Available): String {
        return extension.apkUrl ?: "${extension.repoUrl}/apk/${extension.apkName}"
    }
}
