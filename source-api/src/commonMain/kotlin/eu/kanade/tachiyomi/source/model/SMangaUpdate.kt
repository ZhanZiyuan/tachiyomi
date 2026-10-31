package eu.kanade.tachiyomi.source.model

/** Binary-compatible result for the tachiyomix 1.6 combined update API. */
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
