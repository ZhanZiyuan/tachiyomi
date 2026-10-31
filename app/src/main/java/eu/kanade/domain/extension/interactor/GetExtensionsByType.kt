package eu.kanade.domain.extension.interactor

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {

    fun subscribe(): Flow<Extensions> {
        val showNsfwSources = preferences.showNsfwSource().get()

        return combine(
            preferences.enabledLanguages().changes(),
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
        ) { _activeLanguages, _installed, _untrusted, _available ->
            val (updates, installed) = _installed
                .filter { (showNsfwSources || it.isNsfw.not()) }
                .sortedWith(
                    compareBy<Extension.Installed> { it.isObsolete.not() }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .partition { it.hasUpdate }

            val untrusted = _untrusted
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            val available = _available
                .filter { extension ->
                    _installed.none { it.pkgName == extension.pkgName } &&
                        _untrusted.none { it.pkgName == extension.pkgName } &&
                        (showNsfwSources || extension.isNsfw.not())
                }
                .flatMap { ext ->
                    if (ext.sources.isEmpty()) {
                        return@flatMap if (ext.lang in _activeLanguages) listOf(ext) else emptyList()
                    }
                    ext.sources.filter { it.lang in _activeLanguages }
                        .map { ext.forSource(it) }
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            Extensions(updates, installed, available, untrusted)
        }
    }
}

// Language-specific rows share the APK identity, download progress, and cancel action.
// Row identity belongs in the list key, never in the Android package name.
internal fun Extension.Available.forSource(source: Extension.Available.Source): Extension.Available = copy(
    name = source.name,
    lang = source.lang,
    sources = listOf(source),
)
