package eu.kanade.tachiyomi.extension.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExtensionPackageValidationTest {
    @Test
    fun `catalog cannot install a different package version or signing certificate`() {
        val cert = "a".repeat(64)
        fun valid(pkg: String = "source.example", ver: Long = 7, certs: List<String> = listOf(cert)) =
            matchesExtensionListing("source.example", 7, cert, pkg, ver, certs)
        assertTrue(valid())
        assertFalse(valid(pkg = "other.example"))
        assertFalse(valid(ver = 8))
        assertFalse(valid(certs = listOf("b".repeat(64))))
        assertFalse(valid(certs = emptyList()))
    }
}
