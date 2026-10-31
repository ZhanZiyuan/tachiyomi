package eu.kanade.tachiyomi.extension.util

/** A catalog entry must never be able to install a different package or an unsigned APK. */
internal fun matchesExtensionListing(
    expectedPackage: String,
    expectedVersion: Long,
    expectedSigningKey: String?,
    actualPackage: String,
    actualVersion: Long,
    actualSigningKeys: List<String>,
): Boolean {
    if (expectedPackage != actualPackage || expectedVersion != actualVersion || actualSigningKeys.isEmpty()) {
        return false
    }
    // Old JSON repositories have no signing key; their extensions still require explicit trust.
    if (expectedSigningKey == null) return true
    if (!expectedSigningKey.matches(Regex("[a-fA-F0-9]{64}"))) return false
    return actualSigningKeys.any { it.equals(expectedSigningKey, ignoreCase = true) }
}
