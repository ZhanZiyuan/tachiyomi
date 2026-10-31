package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Mihon's extension store wire format. Field numbers match the upstream schema in
 * data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt.
 * APK and icon locations are supplied by the store; modern stores do not use /apk/<name>.
 */
@Serializable
internal data class ExtensionStoreIndex(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(101) val extensionList: ExtensionStoreList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@Serializable
internal data class ExtensionStoreList(
    @ProtoNumber(1) val extensions: List<StoreExtension> = emptyList(),
)

@Serializable
internal data class StoreExtension(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val packageName: String,
    @ProtoNumber(3) val resources: StoreResources,
    @ProtoNumber(4) val extensionLib: String,
    @ProtoNumber(5) val versionCode: Long,
    @ProtoNumber(6) val versionName: String,
    // Integer preserves forward compatibility with new content-warning values.
    // The current schema uses 0 unspecified, 1 safe, 2 mixed, 3 NSFW.
    @ProtoNumber(7) val contentWarning: Int = 0,
    @ProtoNumber(8) val sources: List<StoreSource> = emptyList(),
)

@Serializable
internal data class StoreResources(
    @ProtoNumber(1) val apkUrl: String,
    @ProtoNumber(2) val iconUrl: String,
)

@Serializable
internal data class StoreSource(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val language: String,
    @ProtoNumber(4) val homeUrl: String = "",
)

@Serializable
internal data class LegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<LegacySource>? = null,
)

@Serializable
internal data class LegacySource(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

internal object ExtensionCatalog {
    // Apply the same limit before and after decompression to bound untrusted catalogs.
    const val MAX_CATALOG_BYTES = 16 * 1024 * 1024

    private val signingKeyPattern = Regex("[a-fA-F0-9]{64}")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val apkNamePattern = Regex("[A-Za-z0-9_.-]+\\.apk")

    fun readBounded(input: InputStream, limit: Int = MAX_CATALOG_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= limit - output.size()) { "Extension catalog exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decompress(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_CATALOG_BYTES) { "Extension catalog exceeds size limit" }
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { readBounded(it) }
        } else {
            bytes
        }
    }

    fun decodeStore(bytes: ByteArray): ExtensionStoreIndex {
        val store = ProtoBuf.decodeFromByteArray<ExtensionStoreIndex>(decompress(bytes))
        require(store.name.isNotBlank()) { "Extension store has no name" }
        require(signingKeyPattern.matches(store.signingKey)) { "Invalid extension store signing key" }
        require(store.extensionList != null || store.extensionListUrl != null) { "Extension store has no catalog" }
        store.extensionListUrl?.let { require(isSecureUrl(it)) { "Insecure extension list URL" } }
        return store
    }

    fun decodeList(bytes: ByteArray): ExtensionStoreList =
        ProtoBuf.decodeFromByteArray(decompress(bytes))

    fun decodeLegacy(bytes: ByteArray, json: Json): List<LegacyExtension> =
        json.decodeFromString(decompress(bytes).decodeToString())

    fun isSecureUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }

    fun toExtensions(
        store: ExtensionStoreIndex,
        list: ExtensionStoreList,
        repoUrl: String,
        supportedVersions: ClosedRange<Double>,
    ): List<Extension.Available> {
        // Validate even when callers supply an already decoded store.
        require(signingKeyPattern.matches(store.signingKey)) { "Invalid extension store signing key" }
        return list.extensions.mapNotNull { entry ->
            val libVersion = entry.extensionLib.toDoubleOrNull() ?: return@mapNotNull null
            if (libVersion !in supportedVersions || !packagePattern.matches(entry.packageName) ||
                entry.name.isBlank() || entry.versionCode < 0 || entry.versionName.isBlank() ||
                !isSecureUrl(entry.resources.apkUrl) || !isSecureUrl(entry.resources.iconUrl)
            ) {
                return@mapNotNull null
            }
            val apkName = entry.resources.apkUrl.toHttpUrlOrNull()!!.pathSegments.last()
            if (!apkNamePattern.matches(apkName)) return@mapNotNull null
            val languages = entry.sources.map { it.language }.filter { it.isNotBlank() }.distinct()
            Extension.Available(
                name = entry.name,
                pkgName = entry.packageName,
                versionName = entry.versionName,
                versionCode = entry.versionCode,
                libVersion = libVersion,
                lang = languages.singleOrNull() ?: "all",
                isNsfw = entry.contentWarning >= 2,
                sources = entry.sources.map {
                    Extension.Available.Source(id = it.id, lang = it.language, name = it.name, baseUrl = it.homeUrl)
                },
                apkName = apkName,
                apkUrl = entry.resources.apkUrl,
                iconUrl = entry.resources.iconUrl,
                repoUrl = repoUrl,
                signingKey = store.signingKey.lowercase(),
            )
        }
    }

    fun legacyToExtensions(
        entries: List<LegacyExtension>,
        repoBaseUrl: String,
        supportedVersions: ClosedRange<Double>,
    ): List<Extension.Available> = entries.mapNotNull { entry ->
        val libVersion = entry.version.substringBeforeLast('.').toDoubleOrNull() ?: return@mapNotNull null
        if (libVersion !in supportedVersions || !packagePattern.matches(entry.pkg) ||
            !apkNamePattern.matches(entry.apk) || entry.name.isBlank() || entry.code < 0
        ) {
            return@mapNotNull null
        }
        Extension.Available(
            name = entry.name.substringAfter("Tachiyomi: "),
            pkgName = entry.pkg,
            versionName = entry.version,
            versionCode = entry.code,
            libVersion = libVersion,
            lang = entry.lang,
            isNsfw = entry.nsfw == 1,
            sources = entry.sources.orEmpty().map {
                Extension.Available.Source(id = it.id, lang = it.lang, name = it.name, baseUrl = it.baseUrl)
            },
            apkName = entry.apk,
            iconUrl = "$repoBaseUrl/icon/${entry.pkg}.png",
            repoUrl = repoBaseUrl,
        )
    }
}
