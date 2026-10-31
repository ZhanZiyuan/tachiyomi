package eu.kanade.tachiyomi.source

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.WeakHashMap

fun Source.getNameForMangaInfo(): String {
    val preferences = Injekt.get<SourcePreferences>()
    val enabledLanguages = preferences.enabledLanguages().get()
        .filterNot { it in listOf("all", "other") }
    val hasOneActiveLanguages = enabledLanguages.size == 1
    val isInEnabledLanguages = lang in enabledLanguages
    return when {
        // For edge cases where user disables a source they got manga of in their library.
        hasOneActiveLanguages && !isInEnabledLanguages -> toString()
        // Hide the language tag when only one language is used.
        hasOneActiveLanguages && isInEnabledLanguages -> name
        else -> toString()
    }
}

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource

/** Modern sources implement getMangaUpdate; its default delegates to legacy suspend/Rx methods. */
suspend fun Source.getMangaDetailsCompat(manga: SManga): SManga =
    getMangaUpdateCompat(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga

suspend fun Source.getChapterListCompat(manga: SManga): List<SChapter> =
    getMangaUpdateCompat(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters

private val sourceUpdateLocks = WeakHashMap<Source, Mutex>()

/** Modern sources reject concurrent updates for the same manga, including background library jobs. */
suspend fun Source.getMangaUpdateCompat(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate {
    val lock = synchronized(sourceUpdateLocks) { sourceUpdateLocks.getOrPut(this) { Mutex() } }
    return lock.withLock { getMangaUpdate(manga, chapters, fetchDetails, fetchChapters) }
}
