package eu.kanade.tachiyomi.ui.reader.narration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.PresetReverb
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class NarrationState(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val preparingChapter: Boolean = false,
    val chapterProgress: Int = 0,
    val chapterReady: Boolean = false,
    /** The visible page has transcript and audio ready, even while the rest is still preparing. */
    val pageReady: Boolean = false,
    /** Pages skipped after a provider or network error; they can be retried without resetting the chapter. */
    val failedPages: List<NarrationPageFailure> = emptyList(),
    val message: String = "Enable AI narration to listen while reading.",
    val pageIndex: Int = -1,
    val lines: List<NarrationLine> = emptyList(),
    val characters: List<NarrationCharacter> = emptyList(),
    val currentLineId: String? = null,
)

/** One reader-session controller. No source images or credentials enter the shared source HTTP client. */
class NarrationController(context: Context) {
    private val appContext = context.applicationContext
    val settings = NarrationSettings(appContext)
    private val store = NarrationStore(appContext)
    private val api = NarrationApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workMutex = Mutex()
    private val audioMutex = Mutex()
    private val mutableState = MutableStateFlow(NarrationState(enabled = settings.enabled))
    val state = mutableState.asStateFlow()

    private var selectedPage: ReaderPage? = null
    private var mangaId = -1L
    private var chapterId = -1L
    private var readingDirection = "right-to-left"
    private var scrollMode = false
    private var visibleTop = 0f
    private var visibleBottom = 1f
    private var hasViewport = true
    private var foreground = true
    private var manuallyPaused = false
    private var manualStartRequested = false
    private var configuration = ""
    private var preparation: Job? = null
    private var preparingPage: ReaderPage? = null
    private var preparedPage: ReaderPage? = null
    private var preparedImageHash: String? = null
    private var playback: Job? = null
    private var playbackGeneration = 0L
    private var manualPlayback = false
    private var prefetch: Job? = null
    private var prefetchSignature: String? = null
    private var activePlayer: PlaybackHandle? = null
    private var voiceList = emptyList<NarrationVoice>()
    private val chapterPages = mutableMapOf<Int, NarrationPage>()
    private val audioReadyPages = mutableSetOf<Int>()
    private var chapterFailed = false
    private var sessionPlaybackRequested = false
    private val played = mutableSetOf<String>()
    private val overrides = mutableMapOf<String, String>()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun selectPage(manga: Long, page: ReaderPage, direction: String, scrolling: Boolean) {
        val chapter = page.chapter.chapter.id ?: return
        val changedChapter = mangaId != manga || chapterId != chapter
        val changed = mangaId != manga || chapterId != chapter || selectedPage?.index != page.index
        readingDirection = direction
        scrollMode = scrolling
        // Keep the object captured by in-flight work when a viewer re-emits the same logical page.
        if (!changed) return
        mangaId = manga
        chapterId = chapter
        selectedPage = page
        preparedPage = null
        preparedImageHash = null
        if (changedChapter) {
            preparation?.cancel()
            preparation = null
            chapterPages.clear()
            audioReadyPages.clear()
            chapterFailed = false
        }
        prefetch?.cancel()
        prefetchSignature = null
        playback?.cancel()
        stopPlayer()
        visibleTop = 0f
        visibleBottom = if (scrolling) 0f else 1f
        hasViewport = !scrolling
        overrides.clear()
        manualStartRequested = false
        mutableState.update {
            it.copy(
                enabled = settings.enabled,
                busy = if (changedChapter) false else it.busy,
                playing = false,
                preparingChapter = if (changedChapter) false else it.preparingChapter,
                chapterProgress = if (changedChapter) 0 else it.chapterProgress,
                chapterReady = if (changedChapter) false else it.chapterReady,
                pageReady = page.index in audioReadyPages,
                failedPages = if (changedChapter) emptyList() else it.failedPages,
                pageIndex = page.index,
                lines = chapterPages[page.index]?.lines.orEmpty(),
                currentLineId = null,
                message = when {
                    !settings.enabled -> "AI narration is off."
                    !changedChapter && it.chapterReady && it.failedPages.isNotEmpty() ->
                        skippedPagesMessage(it.failedPages)
                    !changedChapter && it.chapterReady -> "Chapter ready. Scroll to listen."
                    page.index in audioReadyPages -> "Page ready. Preparing the rest of the chapter..."
                    !changedChapter && it.preparingChapter -> it.message
                    else -> "Preparing chapter..."
                },
            )
        }
        chapterPages[page.index]?.let {
            preparedPage = page
            preparedImageHash = it.imageHash
        }
        if (settings.enabled && foreground) {
            if (page.index in audioReadyPages &&
                (settings.autoPlay || sessionPlaybackRequested) && !manuallyPaused
            ) {
                startPlayback()
            } else {
                prepareSelectedPage()
            }
        }
    }

    fun viewport(top: Float, bottom: Float) {
        if (!top.isFinite() || !bottom.isFinite()) return
        visibleTop = min(top, bottom).coerceIn(0f, 1f)
        visibleBottom = max(top, bottom).coerceIn(visibleTop, 1f)
        if (visibleBottom <= visibleTop) {
            clearViewport()
            return
        }
        val viewportRestored = !hasViewport
        hasViewport = true
        if (viewportRestored && settings.enabled && foreground &&
            preparedPage !== selectedPage && preparation?.isActive != true
        ) {
            prepareSelectedPage()
        }
        if (scrollMode && !manualPlayback && state.value.playing) {
            val current = state.value.lines.firstOrNull { it.id == state.value.currentLineId }
            if (current != null &&
                narrationVisibility(current, true, visibleTop, visibleBottom) == NarrationVisibility.SKIP
            ) {
                played.add(current.id)
                playback?.cancel()
                stopPlayer()
                mutableState.update { it.copy(playing = false, currentLineId = null) }
            }
        }
        if (settings.enabled && (settings.autoPlay || sessionPlaybackRequested) && !manuallyPaused && foreground) startPlayback()
    }

    /** Transition/loading frames must not keep narrating the last page's visible range. */
    fun clearViewport() {
        if (!scrollMode || !hasViewport) return
        hasViewport = false
        visibleTop = 0f
        visibleBottom = 0f
        playback?.cancel()
        prefetch?.cancel()
        prefetchSignature = null
        stopPlayer()
        mutableState.update {
            it.copy(playing = false, currentLineId = null, message = if (it.preparingChapter) it.message else "Scroll to a loaded manga page.")
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        mutableState.update { it.copy(enabled = enabled) }
        if (!enabled) {
            preparation?.cancel()
            prefetch?.cancel()
            audioReadyPages.clear()
            pause()
            mutableState.update {
                it.copy(busy = false, preparingChapter = false, pageReady = false, failedPages = emptyList(), message = "AI narration is off.")
            }
        } else {
            manuallyPaused = false
            mutableState.update { it.copy(paused = false) }
            prepareSelectedPage()
        }
    }

    fun resumeFromBackground() {
        foreground = true
        val nextConfiguration = listOf(
            settings.geminiModel, settings.speechModel, settings.defaultVoiceId,
            settings.geminiKey, settings.elevenLabsKey,
        ).joinToString("|").hashCode().toString()
        if (configuration.isNotEmpty() && configuration != nextConfiguration) {
            voiceList = emptyList()
            preparedPage = null
            preparedImageHash = null
            audioReadyPages.clear()
            chapterFailed = false
            mutableState.update { it.copy(chapterReady = false, pageReady = false, failedPages = emptyList()) }
            state.value.lines.forEach { played.remove(it.id) }
            preparation?.cancel()
            playback?.cancel()
            prefetch?.cancel()
            stopPlayer()
        }
        configuration = nextConfiguration
        mutableState.update { it.copy(enabled = settings.enabled) }
        if (settings.enabled && selectedPage != null) prepareSelectedPage()
    }

    fun suspendForBackground() {
        foreground = false
        preparation?.cancel()
        prefetch?.cancel()
        playback?.cancel()
        stopPlayer()
        mutableState.update { it.copy(busy = false, playing = false, preparingChapter = false) }
    }

    fun playOrPause() {
        if (state.value.preparingChapter && !state.value.pageReady) {
            toggleScrollPlayback()
            return
        }
        if (state.value.playing || playback?.isActive == true) {
            pause()
        } else {
            manuallyPaused = false
            mutableState.update { it.copy(paused = false) }
            manualStartRequested = true
            if (!settings.enabled) setEnabled(true)
            else if (state.value.lines.isEmpty()) prepareSelectedPage()
            else startPlayback(manual = true)
        }
    }

    fun pause() {
        manuallyPaused = true
        manualStartRequested = false
        playback?.cancel()
        prefetch?.cancel()
        prefetchSignature = null
        stopPlayer()
        mutableState.update {
            it.copy(playing = false, paused = true, message = if (it.preparingChapter) it.message else "Paused. Tap Play to continue.")
        }
    }

    /** The floating control mutes/resumes scroll narration without stopping chapter preparation. */
    fun toggleScrollPlayback() {
        if (!state.value.preparingChapter && !state.value.chapterReady) {
            manuallyPaused = false
            sessionPlaybackRequested = true
            manualStartRequested = true
            mutableState.update { it.copy(paused = false) }
            prepareSelectedPage()
            return
        }
        if (!manuallyPaused) {
            pause()
            return
        }
        manuallyPaused = false
        sessionPlaybackRequested = true
        mutableState.update { it.copy(paused = false) }
        if (state.value.pageReady) startPlayback()
        else if (preparation?.isActive != true) {
            manualStartRequested = true
            prepareSelectedPage()
        }
    }

    fun replay() {
        state.value.lines.forEach { played.remove(it.id) }
        playback?.cancel()
        prefetch?.cancel()
        prefetchSignature = null
        stopPlayer()
        manuallyPaused = false
        mutableState.update { it.copy(paused = false) }
        manualStartRequested = true
        if (state.value.lines.isEmpty()) prepareSelectedPage() else startPlayback(manual = true)
    }

    /** Reuses cached successful pages and retries only the pages or clips that were previously skipped. */
    fun retrySkippedPages() {
        if (state.value.failedPages.isEmpty() || preparation?.isActive == true) return
        chapterFailed = false
        mutableState.update {
            it.copy(
                busy = false,
                preparingChapter = false,
                chapterReady = false,
                pageReady = selectedPage?.index in audioReadyPages,
                failedPages = emptyList(),
                message = "Retrying skipped pages...",
            )
        }
        prepareSelectedPage()
    }

    fun resetChapter() {
        val page = selectedPage ?: return
        val manga = mangaId
        val chapter = chapterId
        manualStartRequested = false
        preparedPage = null
        preparedImageHash = null
        chapterPages.clear()
        audioReadyPages.clear()
        chapterFailed = false
        cancelCurrentWork()
        mutableState.update {
            it.copy(busy = true, chapterReady = false, pageReady = false, failedPages = emptyList(), chapterProgress = 0, lines = emptyList(), message = "Resetting chapter narration...")
        }
        scope.launch {
            try {
                workMutex.withLock {
                    audioMutex.withLock { withContext(Dispatchers.IO) { store.resetChapter(manga, chapter) } }
                }
                played.removeAll { it.startsWith(chapter.toString() + "_") }
                if (selectedPage === page) {
                    overrides.clear()
                    mutableState.update { it.copy(busy = false, lines = emptyList(), currentLineId = null) }
                    if (settings.enabled && foreground) prepareSelectedPage()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (selectedPage === page) {
                    mutableState.update { it.copy(busy = false, message = safeError(error)) }
                }
            }
        }
    }

    fun resetCharacters() {
        val manga = mangaId
        if (manga < 0) return
        manualStartRequested = false
        preparedPage = null
        preparedImageHash = null
        chapterPages.clear()
        audioReadyPages.clear()
        chapterFailed = false
        cancelCurrentWork()
        mutableState.update {
            it.copy(busy = true, chapterReady = false, pageReady = false, failedPages = emptyList(), chapterProgress = 0, lines = emptyList(), message = "Resetting character assignments...")
        }
        scope.launch {
            try {
                workMutex.withLock { audioMutex.withLock { withContext(Dispatchers.IO) { store.resetCharacters(manga) } } }
                if (mangaId == manga) {
                    played.clear()
                    overrides.clear()
                    mutableState.update {
                        it.copy(busy = false, characters = emptyList(), lines = emptyList(), currentLineId = null)
                    }
                    if (settings.enabled && foreground) prepareSelectedPage()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mangaId == manga) mutableState.update { it.copy(busy = false, message = safeError(error)) }
            }
        }
    }

    suspend fun voices(): List<NarrationVoice> {
        if (voiceList.isEmpty()) voiceList = api.voices(settings.elevenLabsKey)
        return voiceList
    }

    /** Gemini totals are local tracking; ElevenLabs values are refreshed live from the account. */
    suspend fun usageSummary(): NarrationUsageSummary = NarrationUsageSummary(
        gemini = settings.geminiUsage(),
        elevenLabs = api.subscription(settings.elevenLabsKey),
    )

    fun assignVoice(character: NarrationCharacter, voiceId: String) {
        val manga = mangaId
        if (manga < 0) return
        pause()
        preparation?.cancel()
        audioReadyPages.clear()
        scope.launch {
            try {
                workMutex.withLock {
                    val updated = withContext(Dispatchers.IO) {
                        store.characters(manga).map {
                            if (it.id == character.id) it.copy(voiceId = voiceId, voiceAutoAssigned = false) else it
                        }.also { store.saveCharacters(manga, it) }
                    }
                    if (mangaId == manga) {
                        mutableState.update {
                            it.copy(
                                characters = updated,
                                chapterReady = false,
                                pageReady = false,
                                message = "Preparing the updated character voices...",
                            )
                        }
                        chapterFailed = false
                        prepareSelectedPage()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mangaId == manga) mutableState.update { it.copy(message = safeError(error)) }
            }
        }
    }

    /** Re-match automatically managed characters against the account's age/gender-labelled voices. */
    fun autoMatchCharacterVoices() {
        val manga = mangaId
        if (manga < 0) return
        pause()
        preparation?.cancel()
        audioReadyPages.clear()
        scope.launch {
            try {
                val available = voices()
                if (available.isEmpty()) throw IOException("ElevenLabs returned no voices to match.")
                val updated = workMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val usedVoiceIds = mutableSetOf(settings.defaultVoiceId)
                        val characters = store.characters(manga).map { character ->
                            val manual = character.id == "narrator" ||
                                (!character.voiceAutoAssigned && character.voiceId.isNotBlank() &&
                                    character.voiceId != settings.defaultVoiceId)
                            if (manual) {
                                usedVoiceIds += character.voiceId
                                character
                            } else {
                                val voice = selectNarrationVoice(available, character.voiceProfile, usedVoiceIds)?.id
                                    ?: settings.defaultVoiceId
                                usedVoiceIds += voice
                                character.copy(voiceId = voice, voiceAutoAssigned = true)
                            }
                        }
                        store.saveCharacters(manga, characters)
                        characters
                    }
                }
                if (mangaId == manga) {
                    chapterFailed = false
                    mutableState.update {
                        it.copy(
                            characters = updated,
                            chapterReady = false,
                            pageReady = false,
                            failedPages = emptyList(),
                            message = "Matched distinct character voices. Preparing this page...",
                        )
                    }
                    prepareSelectedPage()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mangaId == manga) mutableState.update { it.copy(message = safeError(error)) }
            }
        }
    }

    fun assignSpeaker(line: NarrationLine, characterId: String) {
        val manga = mangaId
        val chapter = chapterId
        val page = selectedPage ?: return
        val imageHash = preparedImageHash
        pause()
        preparation?.cancel()
        overrides[line.id] = characterId
        val updated = state.value.lines.map { if (it.id == line.id) it.copy(characterId = characterId) else it }
        chapterPages[page.index]?.let { chapterPages[page.index] = it.copy(lines = updated) }
        mutableState.update { it.copy(lines = updated, message = "Speaker corrected. Replay to hear the change.") }
        scope.launch {
            try {
                workMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val currentHash = imageHash ?: readPage(page).hash
                        store.page(manga, chapter, page.index, currentHash)?.let {
                            store.savePage(manga, chapter, it.copy(lines = updated))
                        }
                    }
                }
                if (mangaId == manga && chapterId == chapter) {
                    chapterFailed = false
                    audioReadyPages.remove(page.index)
                    mutableState.update { it.copy(chapterReady = false, pageReady = false) }
                    prepareSelectedPage()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (selectedPage === page) mutableState.update { it.copy(message = safeError(error)) }
            }
        }
    }

    fun close() {
        scope.cancel()
        stopPlayer()
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }

    private fun cancelCurrentWork() {
        preparation?.cancel()
        prefetch?.cancel()
        prefetchSignature = null
        playback?.cancel()
        stopPlayer()
        mutableState.update { it.copy(busy = false, playing = false) }
    }

    private fun prepareSelectedPage() {
        val page = selectedPage ?: return
        if (!foreground || !settings.enabled) return
        if (preparation?.isActive == true) return
        if (state.value.chapterReady) {
            if (manualStartRequested || ((settings.autoPlay || sessionPlaybackRequested) && !manuallyPaused)) {
                val manual = manualStartRequested
                manualStartRequested = false
                startPlayback(manual)
            }
            return
        }
        if (chapterFailed && !manualStartRequested) return
        if (!settings.isConfigured()) {
            mutableState.update { it.copy(busy = false, preparingChapter = false, message = "Add both API keys in Narration settings.") }
            return
        }
        chapterFailed = false
        val manga = mangaId
        val chapter = chapterId
        preparingPage = page
        mutableState.update {
            it.copy(
                busy = true,
                preparingChapter = true,
                chapterReady = false,
                pageReady = page.index in audioReadyPages,
                chapterProgress = 0,
                message = "Preparing the chapter...",
            )
        }
        preparation = scope.launch {
            try {
                delay(350)
                val pages = page.chapter.pages.orEmpty().sortedBy { it.index }
                if (pages.isEmpty()) throw IOException("The chapter has no loaded pages. Reopen the chapter to retry.")
                // Keep one persistent chapter job while the reader scrolls. It fills the following pages first,
                // then backfills earlier pages, instead of restarting whenever the reader advances.
                val pagesByIndex = pages.associateBy { it.index }
                val preparationOrder = narrationPagePreparationOrder(pages.map { it.index }, page.index)
                    .mapNotNull(pagesByIndex::get)
                mutableState.update {
                    it.copy(
                        busy = true,
                        preparingChapter = true,
                        chapterReady = false,
                        chapterProgress = 0,
                        message = "Analyzing the chapter...",
                    )
                }
                for ((index, chapterPage) in preparationOrder.withIndex()) {
                    ensureActive()
                    val result = try {
                        workMutex.withLock { prepareWindow(manga, chapter, chapterPage) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        recordPageFailure(chapterPage.index, "Gemini analysis", error)
                        mutableState.update {
                            it.copy(
                                chapterProgress = narrationPageQueueProgress(index + 1, preparationOrder.size, false),
                                message = "Skipped page ${chapterPage.index + 1}; preparing the next page...",
                            )
                        }
                        continue
                    }
                    ensureActive()
                    if (mangaId != manga || chapterId != chapter) return@launch
                    chapterPages[chapterPage.index] = result.first
                    if (selectedPage?.index == chapterPage.index) {
                        preparedPage = selectedPage
                        preparedImageHash = result.first.imageHash
                    }
                    mutableState.update {
                        it.copy(
                            chapterProgress = narrationPageQueueProgress(index, preparationOrder.size, false),
                            characters = result.second,
                            lines = chapterPages[selectedPage?.index]?.lines.orEmpty(),
                            message = "Preparing page " + (index + 1) + "/" + preparationOrder.size,
                        )
                    }
                    try {
                        preparePageAudio(manga, chapter, chapterPage, result.first.lines)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        recordPageFailure(chapterPage.index, "ElevenLabs audio", error)
                    }
                    mutableState.update {
                        it.copy(
                            chapterProgress = narrationPageQueueProgress(index + 1, preparationOrder.size, false),
                            message = "Prepared pages " + (index + 1) + "/" + preparationOrder.size,
                        )
                    }
                }
                ensureActive()
                if (mangaId != manga || chapterId != chapter) return@launch
                mutableState.update {
                    it.copy(
                        busy = false,
                        preparingChapter = false,
                        chapterReady = true,
                        pageReady = selectedPage?.index in audioReadyPages,
                        chapterProgress = 100,
                        lines = chapterPages[selectedPage?.index]?.lines.orEmpty(),
                        message = when {
                            it.failedPages.isNotEmpty() -> skippedPagesMessage(it.failedPages)
                            manuallyPaused -> "Chapter ready. Narration is paused."
                            else -> "Chapter ready. Scroll to listen."
                        },
                    )
                }
                if (manualStartRequested || ((settings.autoPlay || sessionPlaybackRequested) && !manuallyPaused)) {
                    val manual = manualStartRequested
                    manualStartRequested = false
                    startPlayback(manual)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mangaId == manga && chapterId == chapter) {
                    chapterFailed = true
                    mutableState.update { it.copy(busy = false, preparingChapter = false, chapterReady = false, message = safeError(error)) }
                }
            }
        }
    }

    /** Finishes one page before advancing, while making its first dialogue playable immediately. */
    private suspend fun preparePageAudio(
        manga: Long,
        chapter: Long,
        page: ReaderPage,
        lines: List<NarrationLine>,
    ) {
        val orderedLines = lines.sortedBy { it.order }
        if (orderedLines.isEmpty()) {
            markPageAudioReady(manga, chapter, page)
            return
        }
        for ((index, line) in orderedLines.withIndex()) {
            currentCoroutineContext().ensureActive()
            audio(manga, chapter, line)
            currentCoroutineContext().ensureActive()
            if (mangaId != manga || chapterId != chapter) return
            // Start with the first (topmost) dialogue turn; later turns can continue in the queue.
            if (index == 0) markPageAudioReady(manga, chapter, page)
        }
    }

    /** Provider failures affect one page, never the rest of the background chapter queue. */
    private fun recordPageFailure(pageIndex: Int, stage: String, error: Exception) {
        audioReadyPages.remove(pageIndex)
        val detail = safeError(error).take(240)
        mutableState.update {
            it.copy(
                pageReady = if (selectedPage?.index == pageIndex) false else it.pageReady,
                failedPages = addNarrationPageFailure(it.failedPages, pageIndex, stage, detail),
            )
        }
    }

    private fun skippedPagesMessage(failures: List<NarrationPageFailure>): String {
        val first = failures.firstOrNull() ?: return "Chapter ready. Scroll to listen."
        return "${failures.size} page(s) skipped. Page ${first.pageIndex + 1} ${first.stage}: ${first.message}. Tap Retry skipped pages."
    }

    private fun markPageAudioReady(manga: Long, chapter: Long, page: ReaderPage) {
        audioReadyPages += page.index
        if (mangaId != manga || chapterId != chapter || selectedPage?.index != page.index) return
        preparedPage = selectedPage
        preparedImageHash = chapterPages[page.index]?.imageHash
        mutableState.update {
            it.copy(
                pageReady = true,
                lines = chapterPages[page.index]?.lines.orEmpty(),
                message = if (it.preparingChapter) {
                    "Page ready. Preparing the rest of the chapter..."
                } else {
                    "Page ready. Scroll to listen."
                },
            )
        }
        if ((manualStartRequested || (settings.autoPlay || sessionPlaybackRequested)) && !manuallyPaused) {
            val manual = manualStartRequested
            manualStartRequested = false
            startPlayback(manual)
        }
    }

    private suspend fun prepareWindow(
        manga: Long,
        chapter: Long,
        current: ReaderPage,
    ): Pair<NarrationPage, List<NarrationCharacter>> {
        val currentImage = readPage(current)
        var bank = withContext(Dispatchers.IO) { store.characters(manga) }
        val existing = withContext(Dispatchers.IO) { store.page(manga, chapter, current.index, currentImage.hash) }
        if (existing != null) return existing to bank

        // One request per page keeps a transient Gemini failure isolated and lets the reader move on.
        val window = listOf(currentImage)
        val pages = current.chapter.pages.orEmpty()
        val previousContext = withContext(Dispatchers.IO) {
            pages.firstOrNull { it.index == current.index - 1 && it.status == Page.State.READY && it.stream != null }?.let { previous ->
                val image = readPage(previous)
                store.page(manga, chapter, previous.index, image.hash)?.lines
                    ?.joinToString("\n") { it.characterId + ": " + it.text }?.takeLast(3000)
            }.orEmpty()
        }
        val fragments = window.flatMap { it.fragments }
        val results = mutableListOf<NarrationPage>()
        var sceneContext = previousContext
        for (batch in fragments.chunked(8)) {
            val response = api.analyze(
                settings.geminiKey,
                settings.geminiModel,
                batch.map { it.image },
                bank,
                sceneContext,
                readingDirection,
            )
            settings.recordGeminiUsage(settings.geminiModel, response.usage)
            val analysis = response.pages
            results.addAll(analysis)
            bank = mergeCharacters(manga, bank, analysis.flatMap { it.characters }, fragments)
            withContext(Dispatchers.IO) { store.saveCharacters(manga, bank) }
            val latestDialogue = analysis.flatMap { it.lines }.joinToString("\n") { it.characterId + ": " + it.text }
            sceneContext = (sceneContext + "\n" + latestDialogue).takeLast(8000)
        }
        for (image in window) {
            val combined = mutableListOf<NarrationLine>()
            var previousFragment: ImageFragment? = null
            for (fragment in image.fragments) {
                val previousLines = combined.toList()
                val overlapTop = fragment.top
                val overlapBottom = previousFragment?.let { min(it.top + it.height, fragment.top + fragment.height) }
                    ?: overlapTop
                val result = results.firstOrNull { it.pageIndex == fragment.image.pageIndex }
                    ?: throw IllegalStateException("The analysis did not return every page. Try resetting the chapter.")
                for (line in result.lines.sortedBy { it.order }) {
                    val box = line.box.toMutableList()
                    box[1] = fragment.top + box[1] * fragment.height
                    box[3] = fragment.top + box[3] * fragment.height
                    val translated = line.copy(box = box)
                    // Only compare earlier slices within their actual shared area. Repeated dialogue
                    // within this slice (or elsewhere on the page) must remain separate turns.
                    if (previousLines.none { other ->
                        narrationOverlapDuplicate(other, translated, overlapTop, overlapBottom)
                    }) combined.add(translated)
                }
                previousFragment = fragment
            }
            val normalized = combined.mapIndexed { index, line ->
                line.copy(id = chapter.toString() + "_" + image.page.index + "_" + index, order = index)
            }
            withContext(Dispatchers.IO) {
                val detectedDirection = results.firstOrNull { it.pageIndex == image.page.index }
                    ?.readingDirection ?: "unknown"
                store.savePage(
                    manga,
                    chapter,
                    NarrationPage(
                        pageIndex = image.page.index,
                        imageHash = image.hash,
                        lines = normalized,
                        readingDirection = detectedDirection,
                    ),
                )
            }
        }
        val result = withContext(Dispatchers.IO) { store.page(manga, chapter, current.index, currentImage.hash) }
            ?: throw IllegalStateException("Unable to save page analysis.")
        return result to bank
    }

    private suspend fun mergeCharacters(
        manga: Long,
        existing: List<NarrationCharacter>,
        incoming: List<NarrationCharacter>,
        fragments: List<ImageFragment>,
    ): List<NarrationCharacter> = withContext(Dispatchers.IO) {
        val bank = existing.associateBy { it.id }.toMutableMap()
        if ("narrator" !in bank) {
            bank["narrator"] = NarrationCharacter(
                id = "narrator",
                name = "Narrator / unknown",
                description = "Uncertain or off-panel speaker",
                voiceId = settings.defaultVoiceId,
            )
        }
        val availableVoices = try {
            voices()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        // Upgrade old character banks that gave every character the narrator voice. Their profiles
        // may be unknown until they appear again, but distinct available voices are still better.
        bank.values
            .filter { it.id != "narrator" && !it.voiceAutoAssigned && it.voiceId == settings.defaultVoiceId }
            .forEach { character ->
                val usedVoiceIds = bank.values
                    .filter { it.id != character.id }
                    .mapNotNull { it.voiceId.takeIf(String::isNotBlank) }
                    .toSet()
                val voice = selectNarrationVoice(availableVoices, character.voiceProfile, usedVoiceIds)?.id
                    ?: settings.defaultVoiceId
                bank[character.id] = character.copy(voiceId = voice, voiceAutoAssigned = true)
            }
        for (character in incoming) {
            val old = bank[character.id]
            val preservesManualVoice = old?.voiceId?.takeIf {
                it.isNotBlank() && !old.voiceAutoAssigned && it != settings.defaultVoiceId
            }
            val preservedAutomaticVoice = old?.voiceId?.takeIf {
                it.isNotBlank() && old.voiceAutoAssigned &&
                    (character.voiceProfile == "unknown" || old.voiceProfile == character.voiceProfile)
            }
            val usedVoiceIds = bank.values
                .filter { it.id != character.id }
                .mapNotNull { it.voiceId.takeIf(String::isNotBlank) }
                .toSet()
            val automaticVoice = selectNarrationVoice(
                availableVoices,
                character.voiceProfile,
                usedVoiceIds,
            )?.id ?: settings.defaultVoiceId
            val voice = preservesManualVoice ?: preservedAutomaticVoice ?: automaticVoice
            var reference = old?.referenceFile
            if (reference == null && character.referenceBox.size == 4) {
                fragments.firstOrNull { it.image.pageIndex == character.referencePage }?.let { fragment ->
                    reference = saveReference(manga, character.id, fragment.image.bytes, character.referenceBox)
                }
            }
            // Updated characters move to the end so bounded future requests keep the recent cast.
            bank.remove(character.id)
            bank[character.id] = character.copy(
                voiceId = voice,
                voiceAutoAssigned = preservesManualVoice == null,
                referenceFile = reference,
            )
        }
        bank.values.toList()
    }

    private fun saveReference(manga: Long, character: String, bytes: ByteArray, box: List<Float>): String? = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        try {
            val left = (box[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (box[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = (box[2] * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (box[3] * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val reference = Bitmap.createScaledBitmap(crop, 160, max(1, crop.height * 160 / crop.width).coerceAtMost(320), true)
            try {
                val file = File(appContext.filesDir, "narration/" + manga + "/references/" + hash(character.toByteArray()) + ".jpg")
                file.parentFile?.mkdirs()
                file.outputStream().use { reference.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                file.absolutePath
            } finally {
                if (reference !== crop && reference !== bitmap) reference.recycle()
                if (crop !== bitmap) crop.recycle()
            }
        } finally { bitmap.recycle() }
    }.getOrNull()

    private fun startPlayback(manual: Boolean = false) {
        if (!narrationCanStartPlayback(
                pageReady = state.value.pageReady,
                alreadyPlaying = playback?.isActive == true,
                enabled = settings.enabled,
                foreground = foreground,
                paused = manuallyPaused,
            )
        ) {
            return
        }
        if (scrollMode && !hasViewport && !manual) return
        if (state.value.lines.isEmpty()) return
        if (manual) manualStartRequested = false
        val lines = state.value.lines.sortedBy { it.order }
        var hasEligibleLine = false
        for (line in lines) {
            if (line.id in played) continue
            when (narrationVisibility(line, scrollMode && !manual, visibleTop, visibleBottom)) {
                NarrationVisibility.SKIP -> played.add(line.id)
                NarrationVisibility.WAIT -> break
                NarrationVisibility.PLAY -> {
                    hasEligibleLine = true
                    break
                }
            }
        }
        // A viewport callback can run every frame. An empty playback job must not restart prefetch/TTS.
        if (!hasEligibleLine) return
        val manga = mangaId
        val chapter = chapterId
        val page = selectedPage ?: return
        val generation = ++playbackGeneration
        manualPlayback = manual
        playback = scope.launch {
            try {
                for ((lineIndex, original) in lines.withIndex()) {
                    ensureActive()
                    if (selectedPage !== page || !foreground || !settings.enabled) break
                    if (original.id in played) continue
                    when (narrationVisibility(original, scrollMode && !manual, visibleTop, visibleBottom)) {
                        NarrationVisibility.SKIP -> { played.add(original.id); continue }
                        NarrationVisibility.WAIT -> break
                        NarrationVisibility.PLAY -> Unit
                    }
                    val line = original.copy(characterId = overrides[original.id] ?: original.characterId)
                    mutableState.update { it.copy(playing = true, currentLineId = line.id, message = "Preparing voice…") }
                    val file = audio(manga, chapter, line)
                    ensureActive()
                    // A request can finish after a fast scroll. Do not speak a bubble already passed.
                    when (narrationVisibility(original, scrollMode && !manual, visibleTop, visibleBottom)) {
                        NarrationVisibility.SKIP -> { played.add(original.id); continue }
                        NarrationVisibility.WAIT -> break
                        NarrationVisibility.PLAY -> Unit
                    }
                    mutableState.update { it.copy(message = if (line.innerThought) "Inner thought: ${line.text}" else line.text) }
                    prefetchUpcoming(manga, chapter, page)
                    playFile(file, line.innerThought)
                    ensureActive()
                    played.add(line.id)
                    // Keep dialogue from running together, while preserving zero-delay for readers who prefer it.
                    if (settings.pauseBetweenLinesMs > 0 && lines.drop(lineIndex + 1).any { it.id !in played }) {
                        delay(settings.pauseBetweenLinesMs.toLong())
                    }
                }
                mutableState.update {
                    it.copy(playing = false, currentLineId = null, message = if (scrollMode && !manual) "Scroll to the next dialogue." else "Page narration finished. Replay to listen again.")
                }
                prefetchUpcoming(manga, chapter, page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                manuallyPaused = true
                mutableState.update { it.copy(playing = false, message = safeError(error)) }
            } finally {
                if (playbackGeneration == generation) manualPlayback = false
            }
        }
    }

    private fun prefetchUpcoming(manga: Long, chapter: Long, page: ReaderPage) {
        // Whole-chapter preparation can still be running after the visible page starts speaking.
        if (state.value.chapterReady) return
        if (!settings.autoPlay || !foreground || manuallyPaused || (scrollMode && !hasViewport)) return
        val candidates = state.value.lines.filter {
            it.id !in played && it.id != state.value.currentLineId
        }.take(3)
        if (candidates.isEmpty() || prefetch?.isActive == true) return
        val signature = "$manga:$chapter:${page.index}:" + candidates.joinToString("|") { it.id }
        // A failed speculative request is retried only on demand or when the candidate set changes.
        if (prefetchSignature == signature) return
        prefetchSignature = signature
        prefetch = scope.launch {
            try {
                // Only prepare the next few unplayed lines, keeping scroll-ahead spending bounded.
                for (line in candidates) {
                    if (selectedPage !== page || mangaId != manga || chapterId != chapter) break
                    audio(manga, chapter, line)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Playback surfaces provider failures on demand; speculative work must not interrupt reading.
            }
        }
    }

    private suspend fun audio(manga: Long, chapter: Long, line: NarrationLine): File = audioMutex.withLock {
        val character = state.value.characters.firstOrNull { it.id == line.characterId }
        val voice = if (line.characterId == "narrator") settings.defaultVoiceId
        else character?.voiceId?.takeIf { it.isNotBlank() } ?: settings.defaultVoiceId
        val digest = hash(listOf(settings.speechModel, voice, line.text, line.innerThought, line.emotion, line.delivery).joinToString("\u0000").toByteArray())
        val target = withContext(Dispatchers.IO) { store.audioFile(manga.toString() + "_" + chapter + "_" + digest) }
        if (target.exists() && target.length() > 0) return@withLock target
        val bytes = api.speech(settings.elevenLabsKey, settings.speechModel, voice, line.text, line.emotion, line.delivery)
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
        target
    }

    @Suppress("DEPRECATION")
    private suspend fun playFile(file: File, innerThought: Boolean) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        stopPlayer()
        val handle = PlaybackHandle(MediaPlayer())
        activePlayer = handle
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Handler(Looper.getMainLooper()).post { if (activePlayer === handle) pause() }
            }
        }
        handle.legacyListener = listener
        try {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).build()
                handle.focusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw IllegalStateException("Audio is in use by another app. Tap Play to retry.")
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                val current = handle.player
                current.setAudioAttributes(attributes)
                current.setOnPreparedListener {
                    if (continuation.isActive) {
                        it.playbackParams = it.playbackParams.setSpeed(settings.playbackSpeedPercent / 100f)
                        it.start()
                    }
                }
                current.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                current.setOnErrorListener { _, _, _ ->
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Unable to play this audio. Reset the chapter and retry."))
                    true
                }
                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { releasePlayer(handle) }
                }
                try {
                    current.setDataSource(file.absolutePath)
                    if (innerThought) {
                        // A small-room reverb is a device-local, subtle echo cue for internal monologue.
                        handle.reverb = runCatching {
                            PresetReverb(0, current.audioSessionId).apply {
                                preset = PresetReverb.PRESET_SMALLROOM
                                enabled = true
                            }
                        }.getOrNull()
                    }
                    current.prepareAsync()
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } finally {
            // A canceled job may finish after Replay has already created its replacement player.
            releasePlayer(handle)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopPlayer() {
        activePlayer?.let(::releasePlayer)
    }

    @Suppress("DEPRECATION")
    private fun releasePlayer(handle: PlaybackHandle) {
        if (handle.released) return
        handle.released = true
        if (activePlayer === handle) activePlayer = null
        runCatching { handle.reverb?.release() }
        runCatching {
            handle.player.setOnPreparedListener(null)
            handle.player.setOnCompletionListener(null)
            handle.player.setOnErrorListener(null)
            handle.player.release()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            handle.focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            handle.legacyListener?.let { audioManager.abandonAudioFocus(it) }
        }
    }

    private suspend fun readPage(page: ReaderPage): PreparedPage = coroutineScope {
        if (page.stream == null || page.status != Page.State.READY) {
            val loading = launch(Dispatchers.IO) { page.chapter.pageLoader?.loadPage(page) }
            try {
                val status = withTimeoutOrNull(45_000) {
                    page.statusFlow.first { it == Page.State.READY || it == Page.State.ERROR }
                } ?: throw IOException("The manga page took too long to load. Tap Play to retry.")
                if (status == Page.State.ERROR) throw IOException("The manga page could not load. Retry its download first.")
            } finally { loading.cancel() }
        }
        withContext(Dispatchers.IO) {
            val source = page.stream ?: throw IllegalStateException("Wait for the manga page to finish loading, then tap Play.")
            val bytes = source().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count != -1) {
                    if (output.size() + count > 32 * 1024 * 1024) throw IllegalStateException("This page is too large for narration (32 MB limit).")
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("This image format cannot be analyzed on this device.")
            var sample = 1
            while (bounds.outWidth / sample > 1400) sample *= 2
            val tileHeight = min(bounds.outHeight.toLong(), bounds.outWidth.toLong() * 3).toInt().coerceAtLeast(1)
            val overlap = if (tileHeight < bounds.outHeight) max(1, tileHeight / 16) else 0
            val tileCount = ceil((bounds.outHeight - overlap).toDouble() / (tileHeight - overlap)).toInt()
            if (tileCount > 24) throw IllegalStateException("This strip is too long to analyze at once. Try a source with shorter page images.")
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                ?: throw IllegalStateException("Unable to decode this page for narration.")
            val fragments = mutableListOf<ImageFragment>()
            try {
                var top = 0
                var tile = 0
                while (top < bounds.outHeight) {
                    ensureActive()
                    val bottom = min(bounds.outHeight, top + tileHeight)
                    val bitmap = decoder.decodeRegion(Rect(0, top, bounds.outWidth, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: throw IllegalStateException("Unable to decode part of this page.")
                    val jpeg = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray() } finally { bitmap.recycle() }
                    fragments.add(ImageFragment(AnalysisImage(page.index * 100 + tile, "image/jpeg", jpeg), top.toFloat() / bounds.outHeight, (bottom - top).toFloat() / bounds.outHeight))
                    if (bottom == bounds.outHeight) break
                    top = bottom - overlap
                    tile++
                }
            } finally { decoder.recycle() }
            PreparedPage(page, hash(bytes), fragments)
        }
    }

    private fun safeError(error: Exception): String {
        var message = error.message ?: "Narration failed. Check your connection and API settings."
        listOf(settings.geminiKey, settings.elevenLabsKey).filter { it.isNotEmpty() }.forEach { message = message.replace(it, "[redacted]") }
        return message.take(500)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class PreparedPage(val page: ReaderPage, val hash: String, val fragments: List<ImageFragment>)
    private data class ImageFragment(val image: AnalysisImage, val top: Float, val height: Float)
    private class PlaybackHandle(val player: MediaPlayer) {
        var focusRequest: AudioFocusRequest? = null
        var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
        var reverb: PresetReverb? = null
        var released = false
    }
}
