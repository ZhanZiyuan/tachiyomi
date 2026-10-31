package eu.kanade.tachiyomi.ui.reader.narration

import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

@Serializable
data class NarrationCharacter(
    val id: String,
    val name: String,
    val description: String,
    /** A conservative fictional-character voice category selected from the visible page. */
    val voiceProfile: String = "unknown",
    val voiceId: String = "",
    /** Manual choices are never replaced by automatic matching. */
    val voiceAutoAssigned: Boolean = false,
    val referenceFile: String? = null,
    val referencePage: Int? = null,
    val referenceBox: List<Float> = emptyList(),
)

@Serializable
data class NarrationLine(
    val id: String,
    val text: String,
    val characterId: String = "narrator",
    /** True only for a character's internal monologue, not ordinary narration. */
    val innerThought: Boolean = false,
    val emotion: String = "neutral",
    val delivery: String = "normal",
    /** Left, top, right, bottom, relative to the original page dimensions. */
    val box: List<Float> = listOf(0f, 0f, 1f, 1f),
    /** Panel sequence in the detected visual reading flow, starting at zero. */
    val panelIndex: Int = 0,
    /** Sequence inside its panel, starting at zero. */
    val order: Int = 0,
)

@Serializable
data class NarrationPage(
    val pageIndex: Int,
    val imageHash: String,
    val lines: List<NarrationLine>,
    /** Visual panel/bubble order detected for this page, independent of the reader's paging direction. */
    val readingDirection: String = "unknown",
    /** The user-selected narration-order mode that produced this cached page. */
    val readingOrderMode: String = "auto",
    val characters: List<NarrationCharacter> = emptyList(),
)

@Serializable
data class NarrationVoice(
    val id: String,
    val name: String,
    val gender: String = "",
    val age: String = "",
)

/** Token counts returned by Gemini for one successful page-analysis request. */
data class GeminiUsage(val promptTokens: Long = 0, val outputTokens: Long = 0)

data class NarrationAnalysis(val pages: List<NarrationPage>, val usage: GeminiUsage)

/** Persistent, on-device tracking from this app only; it cannot see other project usage. */
data class GeminiUsageTotals(
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedCostNanosUsd: Long = 0,
    val hasKnownPrice: Boolean = false,
)

/** Live account values returned by ElevenLabs' subscription endpoint. */
data class ElevenLabsSubscription(
    val tier: String,
    val creditsUsed: Long,
    val creditLimit: Long,
    val overageAmount: String = "",
    val currency: String = "USD",
) {
    val creditsRemaining: Long get() = (creditLimit - creditsUsed).coerceAtLeast(0)
}

data class NarrationUsageSummary(
    val gemini: GeminiUsageTotals,
    val elevenLabs: ElevenLabsSubscription,
)

data class NarrationPageFailure(
    val pageIndex: Int,
    val stage: String,
    val message: String,
)

data class AnalysisImage(val pageIndex: Int, val mimeType: String, val bytes: ByteArray)

internal const val MAX_NARRATION_LINE_CHARACTERS = 4000

/** Count completed work, reserving 100% for a fully committed chapter. */
internal fun narrationChapterProgress(pagesDone: Int, pagesTotal: Int, voicesDone: Int, voicesTotal: Int, ready: Boolean): Int {
    if (ready) return 100
    if (pagesTotal <= 0) return 0
    val analysis = pagesDone.coerceIn(0, pagesTotal).toLong() * 40 / pagesTotal
    val speech = if (voicesTotal > 0 && pagesDone >= pagesTotal) {
        voicesDone.coerceIn(0, voicesTotal).toLong() * 60 / voicesTotal
    } else 0
    return (analysis + speech).toInt().coerceIn(0, 99)
}

/** Prioritize the reader's current page, then continue forwards before backfilling earlier pages. */
internal fun narrationPagePreparationOrder(pageIndexes: List<Int>, currentPageIndex: Int): List<Int> {
    val ordered = pageIndexes.distinct().sorted()
    val currentPosition = ordered.indexOf(currentPageIndex)
    return if (currentPosition < 0) ordered else ordered.drop(currentPosition) + ordered.take(currentPosition)
}

/** The chapter indicator advances only after a page has both transcript and usable audio. */
internal fun narrationPageQueueProgress(pagesDone: Int, pagesTotal: Int, ready: Boolean): Int {
    if (ready) return 100
    if (pagesTotal <= 0) return 0
    return (pagesDone.coerceIn(0, pagesTotal).toLong() * 100 / pagesTotal).toInt().coerceAtMost(99)
}

/** Gemini paid-tier Standard-list-price estimate in nanodollars, or null for an unknown model. */
internal fun geminiPaidTierEstimateNanos(model: String, inputTokens: Long, outputTokens: Long): Long? {
    val normalized = model.removePrefix("models/").lowercase()
    val pricePerTokenNanos = when {
        normalized == "gemini-2.5-flash" -> 300L to 2_500L
        normalized == "gemini-2.5-flash-lite" -> 100L to 400L
        else -> return null
    }
    return inputTokens.coerceAtLeast(0) * pricePerTokenNanos.first +
        outputTokens.coerceAtLeast(0) * pricePerTokenNanos.second
}

/** Keep a reader's explicit mode, but make untouched manga use the narration-friendly pager. */
internal fun narrationUsesVerticalPager(enabled: Boolean, savedReadingMode: Int): Boolean =
    enabled && savedReadingMode == 0

/** A visible page may speak while the background chapter queue is still running. */
internal fun narrationCanStartPlayback(
    pageReady: Boolean,
    alreadyPlaying: Boolean,
    enabled: Boolean,
    foreground: Boolean,
    paused: Boolean,
): Boolean = pageReady && !alreadyPlaying && enabled && foreground && !paused

internal fun normalizeNarrationReadingDirection(value: String, fallback: String): String {
    val normalizedFallback = when (fallback.lowercase()) {
        "left-to-right", "ltr" -> "left-to-right"
        "top-to-bottom", "vertical" -> "top-to-bottom"
        else -> "right-to-left"
    }
    return when (value.lowercase().trim()) {
        "left-to-right", "ltr" -> "left-to-right"
        "right-to-left", "rtl" -> "right-to-left"
        "top-to-bottom", "vertical", "ttb" -> "top-to-bottom"
        else -> normalizedFallback
    }
}

internal fun normalizeNarrationReadingOrder(value: String): String = when (value.lowercase().trim()) {
    "right-to-left", "rtl" -> "right-to-left"
    "left-to-right", "ltr" -> "left-to-right"
    "top-to-bottom", "vertical", "ttb" -> "top-to-bottom"
    else -> "auto"
}

/** A forced narration order always wins over reader paging and Gemini's detected direction. */
internal fun narrationEffectiveReadingDirection(order: String, readerFallback: String): String {
    val normalizedOrder = normalizeNarrationReadingOrder(order)
    return if (normalizedOrder == "auto") {
        normalizeNarrationReadingDirection("unknown", readerFallback)
    } else {
        normalizedOrder
    }
}

internal fun normalizeNarrationVoiceProfile(value: String): String =
    value.lowercase().trim().takeIf { it in narrationVoiceProfiles } ?: "unknown"

/** Chooses a distinct labelled ElevenLabs voice whenever the account provides one. */
internal fun selectNarrationVoice(
    voices: List<NarrationVoice>,
    profile: String,
    usedVoiceIds: Set<String>,
): NarrationVoice? = voices.sortedWith(
    compareBy<NarrationVoice> { narrationVoicePenalty(it, normalizeNarrationVoiceProfile(profile)) }
        .thenBy { it.id in usedVoiceIds }
        .thenBy { it.id },
).firstOrNull()

private fun narrationVoicePenalty(voice: NarrationVoice, profile: String): Int {
    val isFemale = voice.gender == "female"
    val isMale = voice.gender == "male"
    val isYoung = voice.age == "young"
    val isAdult = voice.age == "middle_aged" || voice.age == "old"
    return when (profile) {
        "female_child" -> when { isFemale && isYoung -> 0; isFemale -> 1; isYoung -> 2; else -> 3 }
        "male_child" -> when { isMale && isYoung -> 0; isMale -> 1; isYoung -> 2; else -> 3 }
        "female_adult" -> when { isFemale && isAdult -> 0; isFemale -> 1; isAdult -> 2; else -> 3 }
        "male_adult" -> when { isMale && isAdult -> 0; isMale -> 1; isAdult -> 2; else -> 3 }
        "child" -> if (isYoung) 0 else 2
        "adult" -> if (isAdult) 0 else 2
        else -> 0
    }
}

internal fun addNarrationPageFailure(
    failures: List<NarrationPageFailure>,
    pageIndex: Int,
    stage: String,
    message: String,
): List<NarrationPageFailure> =
    (failures.filterNot { it.pageIndex == pageIndex } + NarrationPageFailure(pageIndex, stage, message))
        .sortedBy { it.pageIndex }

internal val narrationIdentifierPattern = Regex("[A-Za-z0-9_-]{1,80}")

internal fun normalizeNarrationBox(box: List<Float>): List<Float> {
    if (box.size != 4 || box.any { !it.isFinite() }) return listOf(0f, 0f, 1f, 1f)
    val result = box.map { it.coerceIn(0f, 1f) }
    return if (result[2] > result[0] && result[3] > result[1]) result else listOf(0f, 0f, 1f, 1f)
}

/** A model response cannot choose executable voice settings or a local file to upload. */
internal fun normalizeNarrationCharacter(
    character: NarrationCharacter,
    requestedPages: Set<Int>,
): NarrationCharacter? {
    if (!character.id.matches(narrationIdentifierPattern) || character.id == "narrator") return null
    val referencePage = character.referencePage?.takeIf { it in requestedPages }
    return character.copy(
        name = character.name.trim().take(100).ifBlank { "Unknown character" },
        description = character.description.trim().take(700),
        voiceProfile = normalizeNarrationVoiceProfile(character.voiceProfile),
        voiceId = "",
        voiceAutoAssigned = false,
        referenceFile = null,
        referencePage = referencePage,
        referenceBox = if (referencePage != null && character.referenceBox.size == 4) {
            normalizeNarrationBox(character.referenceBox)
        } else {
            emptyList()
        },
    )
}

internal fun normalizeNarrationLines(
    pageIndex: Int,
    lines: List<NarrationLine>,
    characterIds: Set<String>,
): List<NarrationLine> {
    if (lines.size > 150) throw IOException("The narration transcript has too many lines.")
    return lines.sortedWith(compareBy<NarrationLine> { it.panelIndex.coerceIn(0, 99) }.thenBy { it.order })
        .mapIndexedNotNull { index, line ->
        val text = line.text.trim()
        if (text.isBlank()) return@mapIndexedNotNull null
        if (text.length > MAX_NARRATION_LINE_CHARACTERS) {
            throw IOException("A dialogue turn is too long. Try analyzing a smaller page section.")
        }
        line.copy(
            id = "p${pageIndex}_line_$index",
            text = text,
            characterId = line.characterId.takeIf {
                it in characterIds && it.matches(narrationIdentifierPattern)
            } ?: "narrator",
            innerThought = line.innerThought,
            emotion = line.emotion.lowercase().takeIf { it in narrationEmotions } ?: "neutral",
            delivery = line.delivery.lowercase().takeIf { it in narrationDeliveries } ?: "normal",
            box = normalizeNarrationBox(line.box),
            panelIndex = line.panelIndex.coerceIn(0, 99),
            order = index,
        )
    }
}

internal fun narrationAudioFileName(cacheKey: String): String {
    require(cacheKey.matches(Regex("[0-9]+_[0-9]+_[a-f0-9]{64}"))) { "Invalid narration audio key." }
    return "$cacheKey.mp3"
}

private val narrationEmotions = setOf(
    "neutral", "happy", "sad", "angry", "afraid", "surprised", "curious", "excited", "calm",
)
private val narrationDeliveries = setOf("normal", "whispering", "shouting", "crying", "laughing")
private val narrationVoiceProfiles = setOf(
    "female_child", "male_child", "female_adult", "male_adult", "child", "adult", "unknown",
)

internal enum class NarrationVisibility { SKIP, WAIT, PLAY }

/** Pure viewport gate, shared by the pre-launch check and playback after a network request. */
internal fun narrationVisibility(
    line: NarrationLine,
    scrolling: Boolean,
    visibleTop: Float,
    visibleBottom: Float,
): NarrationVisibility {
    if (!scrolling) return NarrationVisibility.PLAY
    if (!visibleTop.isFinite() || !visibleBottom.isFinite() || visibleBottom <= visibleTop) {
        return NarrationVisibility.WAIT
    }
    val box = normalizeNarrationBox(line.box)
    return when {
        box[3] < visibleTop - 0.15f -> NarrationVisibility.SKIP
        box[1] > visibleBottom + 0.03f -> NarrationVisibility.WAIT
        else -> NarrationVisibility.PLAY
    }
}

/** Same text alone is insufficient: two turns must share the same location in overlapping slices. */
internal fun narrationOverlapDuplicate(
    previous: NarrationLine,
    current: NarrationLine,
    overlapTop: Float,
    overlapBottom: Float,
): Boolean {
    if (overlapBottom <= overlapTop || previous.text.trim() != current.text.trim()) return false
    val a = normalizeNarrationBox(previous.box)
    val b = normalizeNarrationBox(current.box)
    val fullPage = listOf(0f, 0f, 1f, 1f)
    if (a == fullPage || b == fullPage) return false
    val aTop = max(a[1], overlapTop)
    val aBottom = min(a[3], overlapBottom)
    val bTop = max(b[1], overlapTop)
    val bBottom = min(b[3], overlapBottom)
    if (aBottom <= aTop || bBottom <= bTop) return false
    val horizontalIntersection = min(a[2], b[2]) - max(a[0], b[0])
    val verticalIntersection = min(aBottom, bBottom) - max(aTop, bTop)
    return horizontalIntersection >= min(a[2] - a[0], b[2] - b[0]) * 0.5f &&
        verticalIntersection >= min(aBottom - aTop, bBottom - bTop) * 0.5f
}
