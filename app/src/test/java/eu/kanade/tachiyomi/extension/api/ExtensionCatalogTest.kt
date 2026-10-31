package eu.kanade.tachiyomi.extension.api

import eu.kanade.domain.extension.interactor.forSource
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.matchesExtensionListing
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ExtensionCatalogTest {
    private fun publishedStore(): ExtensionStoreIndex = javaClass
        .getResourceAsStream("/extension/keiyoushi-sample.pb")!!.use { ExtensionCatalog.decodeStore(it.readBytes()) }

    @Test
    fun `reads publisher gzip protobuf and uses explicit resources and library version`() {
        val store = publishedStore()
        val entries = ExtensionCatalog.toExtensions(store, store.extensionList!!, "https://example.com/index.pb", 1.4..1.6)
        assertEquals("Keiyoushi", store.name)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("MangaDex", entry.name)
        assertEquals(1.6, entry.libVersion)
        assertEquals("all", entry.lang)
        assertTrue(entry.sources.any { it.lang == "en" })
        assertTrue(entry.apkUrl!!.startsWith("https://github.com/"))
        assertEquals("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2", entry.signingKey)
    }

    @Test
    fun `language rows preserve verifiable APK identity and have stable distinct list keys`() {
        val store = publishedStore()
        val entry = ExtensionCatalog.toExtensions(store, store.extensionList!!, "https://example.com/index.pb", 1.4..1.6).single()
        val rows = entry.sources.map { entry.forSource(it) }
        assertTrue(rows.size > 1)
        rows.forEach { row ->
            assertTrue(
                matchesExtensionListing(
                    row.pkgName, row.versionCode, row.signingKey,
                    "eu.kanade.tachiyomi.extension.all.mangadex", 106000,
                    listOf("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"),
                ),
            )
        }
        val keys = rows.map { ExtensionUiModel.Item(it, InstallStep.Idle).key }
        assertEquals(rows.size, keys.toSet().size)
        assertEquals(keys.first(), ExtensionUiModel.Item(rows.first(), InstallStep.Downloading).key)
    }

    @Test
    fun `insecure downloads and unsupported APIs never become installable entries`() {
        val store = publishedStore()
        val original = store.extensionList!!.extensions.single()
        val unsafe = original.copy(resources = original.resources.copy(apkUrl = "http://example.com/a.apk"))
        val future = original.copy(extensionLib = "1.7")
        assertTrue(ExtensionCatalog.toExtensions(store, ExtensionStoreList(listOf(unsafe, future)), "https://example.com/index.pb", 1.4..1.6).isEmpty())
    }

    @Test
    fun `missing signing identity and oversized catalog are rejected`() {
        val bad = publishedStore().copy(signingKey = "not-a-certificate")
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionCatalog.decodeStore(ProtoBuf.encodeToByteArray(bad))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionCatalog.readBounded(ByteArrayInputStream(ByteArray(1025)), 1024)
        }
    }
}
