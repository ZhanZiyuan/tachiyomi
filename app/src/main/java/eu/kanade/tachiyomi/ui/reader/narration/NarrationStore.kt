package eu.kanade.tachiyomi.ui.reader.narration

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Private, persistent narration data. Call disk operations from an IO dispatcher. */
class NarrationStore(context: Context) {
    private val root = File(context.filesDir, "narration")
    private val audio = File(root, "audio")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun characters(mangaId: Long): List<NarrationCharacter> = synchronized(lock) {
        read<List<NarrationCharacter>>(File(series(mangaId), "characters.json")).orEmpty()
    }

    fun saveCharacters(mangaId: Long, characters: List<NarrationCharacter>) = synchronized(lock) {
        require(characters.size <= 500) { "Too many narration characters." }
        write(File(series(mangaId), "characters.json"), json.encodeToString(characters))
    }

    fun page(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageHash: String,
        readingOrderMode: String? = null,
    ): NarrationPage? = synchronized(lock) {
        require(pageIndex >= 0) { "Invalid narration page." }
        read<NarrationPage>(File(chapter(mangaId, chapterId), "$pageIndex.json"))
            ?.takeIf {
                it.imageHash == imageHash && it.pageIndex == pageIndex &&
                    (readingOrderMode == null || it.readingOrderMode == normalizeNarrationReadingOrder(readingOrderMode))
            }
    }

    fun savePage(mangaId: Long, chapterId: Long, page: NarrationPage) = synchronized(lock) {
        require(page.pageIndex >= 0 && page.imageHash.matches(hashPattern)) { "Invalid narration page." }
        write(File(chapter(mangaId, chapterId), "${page.pageIndex}.json"), json.encodeToString(page))
    }

    /** Keys are mangaId_chapterId_SHA256 so resetting one chapter also removes its audio. */
    fun audioFile(cacheKey: String): File = synchronized(lock) {
        val fileName = narrationAudioFileName(cacheKey)
        ensureDirectory(audio)
        File(audio, fileName)
    }

    fun clearAudio(mangaId: Long, chapterId: Long) = synchronized(lock) {
        chapter(mangaId, chapterId) // Validate identifiers before constructing the prefix.
        deleteAudio("${mangaId}_${chapterId}_")
    }

    fun resetChapter(mangaId: Long, chapterId: Long) = synchronized(lock) {
        val chapterDirectory = chapter(mangaId, chapterId)
        if (chapterDirectory.exists() && !chapterDirectory.deleteRecursively()) {
            throw IOException("Could not reset the chapter narration.")
        }
        deleteAudio("${mangaId}_${chapterId}_")
    }

    /** Invalidate analyzed pages too: their speaker IDs refer to this series' character bank. */
    fun resetCharacters(mangaId: Long) = synchronized(lock) {
        val seriesDirectory = series(mangaId)
        if (seriesDirectory.exists() && !seriesDirectory.deleteRecursively()) {
            throw IOException("Could not reset the character assignments.")
        }
        deleteAudio("${mangaId}_")
    }

    private fun series(mangaId: Long): File {
        require(mangaId >= 0) { "Invalid narration manga." }
        return File(root, mangaId.toString())
    }

    private fun chapter(mangaId: Long, chapterId: Long): File {
        require(chapterId >= 0) { "Invalid narration chapter." }
        return File(series(mangaId), chapterId.toString())
    }

    private fun deleteAudio(prefix: String) {
        audio.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach {
            if (!it.delete()) throw IOException("Could not remove cached narration audio.")
        }
    }

    private inline fun <reified T> read(file: File): T? {
        val atomic = AtomicFile(file)
        return runCatching {
            atomic.openRead().use {
                val bytes = it.readBytesWithLimit(MAX_JSON_BYTES)
                json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    private fun write(file: File, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Narration data is too large." }
        ensureDirectory(file.parentFile ?: throw IOException("Missing narration directory."))
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create the narration cache.")
    }

    companion object {
        private val lock = Any()
        private val hashPattern = Regex("[a-f0-9]{64}")
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    }
}

internal fun java.io.InputStream.readBytesWithLimit(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) throw IOException("Narration response exceeds the size limit.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
