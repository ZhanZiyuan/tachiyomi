package eu.kanade.tachiyomi.extension.api

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toDomainManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getChapterListCompat
import eu.kanade.tachiyomi.source.getMangaDetailsCompat
import eu.kanade.tachiyomi.source.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import rx.Observable
import tachiyomi.domain.chapter.model.Chapter

class SourceCompatibilityTest {
    @Test
    fun `legacy Rx source still supplies details and chapters through new bridge`() = runBlocking {
        val manga = SManga.create().apply { url = "/manga"; title = "Legacy" }
        val chapter = SChapter.create().apply { url = "/chapter"; name = "First" }
        val source = object : Source {
            override val id = 1L
            override val name = "Legacy"
            override fun fetchMangaDetails(manga: SManga) = Observable.just(manga)
            override fun fetchChapterList(manga: SManga) = Observable.just(listOf(chapter))
        }
        assertSame(manga, source.getMangaDetailsCompat(manga))
        assertEquals(listOf(chapter), source.getChapterListCompat(manga))
    }

    @Test
    fun `modern combined update source works through existing reader calls`() = runBlocking {
        val manga = SManga.create().apply { url = "/manga"; title = "Modern" }
        val source = object : Source {
            override val id = 2L
            override val name = "Modern"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) =
                SMangaUpdate(manga.copy().apply { title = "Updated" }, emptyList())
        }
        assertEquals("Updated", source.getMangaDetailsCompat(manga).title)
        assertTrue(source.getChapterListCompat(manga).isEmpty())
    }

    @Test
    fun `legacy suspend overrides remain usable without Rx implementations`() = runBlocking {
        val manga = SManga.create().apply { url = "/manga"; title = "Suspend" }
        val source = object : Source {
            override val id = 3L
            override val name = "Legacy suspend"
            override suspend fun getMangaDetails(manga: SManga) = manga.copy().apply { title = "Updated" }
            override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        }
        assertEquals("Updated", source.getMangaDetailsCompat(manga).title)
        assertTrue(source.getChapterListCompat(manga).isEmpty())
    }

    @Test
    fun `opaque metadata survives manga and chapter domain conversions`() {
        val memo = buildJsonObject { put("source_token", "required-to-open-pages") }
        val manga = SManga.create().apply { url = "/manga"; title = "Modern"; this.memo = memo }
        assertEquals(memo, manga.copy().memo)
        assertEquals(memo, manga.toDomainManga(2).toSManga().memo)
        val chapter = SChapter.create().apply { url = "/chapter"; name = "First"; this.memo = memo }
        assertEquals(memo, Chapter.create().copyFromSChapter(chapter).toSChapter().memo)
    }
}
