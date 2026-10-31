package eu.kanade.tachiyomi.extension.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExtensionRepoUrlTest {
    @Test
    fun `documented modern GitHub link becomes an equivalent direct index URL`() {
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            ExtensionRepoUrl.normalize(" https://github.com/keiyoushi/extensions/raw/repo/index.pb\n"),
        )
    }

    @Test
    fun `old preferences and newly entered JSON repos resolve to the same index`() {
        val base = "https://raw.githubusercontent.com/Riztard/tachiyomi-extensions/repo"
        assertEquals(base, ExtensionRepoUrl.normalize("$base/index.min.json"))
        assertEquals("$base/index.min.json", ExtensionRepoUrl.indexUrl(base))
        assertEquals("$base/index.min.json", ExtensionRepoUrl.indexUrl("$base/index.min.json"))
        assertEquals("$base/index.pb", ExtensionRepoUrl.indexUrl("$base/index.pb"))
    }

    @Test
    fun `rejects insecure malformed and ambiguous repo URLs`() {
        listOf(
            "http://example.com/index.pb",
            "https://user:secret@example.com/index.pb",
            "https://example.com/index.pb?key=secret",
            "https://example.com/index.pb#anchor",
            "https://example.com/not-an-index",
            "https://example.com/index.pb/",
            "file:///index.pb",
        ).forEach { assertNull(ExtensionRepoUrl.normalize(it), it) }
    }
}
