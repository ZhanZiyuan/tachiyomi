package eu.kanade.tachiyomi.ui.reader.narration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class NarrationValidationTest {
    @Test
    fun `chapter progress cannot report ready before every clip is committed`() {
        assertEquals(0, narrationChapterProgress(0, 20, 0, 100, false))
        assertEquals(20, narrationChapterProgress(10, 20, 0, 100, false))
        assertEquals(40, narrationChapterProgress(20, 20, 0, 100, false))
        assertEquals(70, narrationChapterProgress(20, 20, 50, 100, false))
        assertEquals(99, narrationChapterProgress(20, 20, 100, 100, false))
        assertEquals(100, narrationChapterProgress(20, 20, 100, 100, true))
        assertEquals(40, narrationChapterProgress(20, 20, 0, 0, false))
        assertEquals(0, narrationChapterProgress(-2, 20, -1, 100, false))
    }

    @Test
    fun `chapter preparation keeps moving forward from the page being read`() {
        assertEquals(
            listOf(4, 5, 6, 0, 1, 2, 3),
            narrationPagePreparationOrder(listOf(0, 1, 2, 3, 4, 5, 6), currentPageIndex = 4),
        )
    }

    @Test
    fun `page queue progress reaches ready only after its final page`() {
        assertEquals(0, narrationPageQueueProgress(0, 10, false))
        assertEquals(50, narrationPageQueueProgress(5, 10, false))
        assertEquals(99, narrationPageQueueProgress(10, 10, false))
        assertEquals(100, narrationPageQueueProgress(10, 10, true))
    }

    @Test
    fun `forced narration order wins over the reader direction`() {
        assertEquals(
            "right-to-left",
            narrationEffectiveReadingDirection("right-to-left", "left-to-right"),
        )
        assertEquals(
            "left-to-right",
            narrationEffectiveReadingDirection("auto", "left-to-right"),
        )
        assertEquals("auto", normalizeNarrationReadingOrder("anything else"))
    }

    @Test
    fun `first ready page can speak while the rest of a chapter prepares`() {
        assertTrue(narrationCanStartPlayback(true, false, true, true, false))
        assertFalse(narrationCanStartPlayback(false, false, true, true, false))
        assertFalse(narrationCanStartPlayback(true, true, true, true, false))
        assertFalse(narrationCanStartPlayback(true, false, true, true, true))
    }

    @Test
    fun `narration defaults untouched manga to vertical paged reading`() {
        assertTrue(narrationUsesVerticalPager(enabled = true, savedReadingMode = 0))
        assertFalse(narrationUsesVerticalPager(enabled = false, savedReadingMode = 0))
        assertFalse(narrationUsesVerticalPager(enabled = true, savedReadingMode = 3))
    }

    @Test
    fun `labelled voices differentiate fictional children and adults`() {
        val voices = listOf(
            NarrationVoice("female_young", "Young female", gender = "female", age = "young"),
            NarrationVoice("female_adult", "Adult female", gender = "female", age = "middle_aged"),
            NarrationVoice("male_young", "Young male", gender = "male", age = "young"),
            NarrationVoice("male_adult", "Adult male", gender = "male", age = "middle_aged"),
        )

        assertEquals("female_young", selectNarrationVoice(voices, "female_child", emptySet())?.id)
        assertEquals("female_adult", selectNarrationVoice(voices, "female_adult", emptySet())?.id)
        assertEquals("male_young", selectNarrationVoice(voices, "male_child", emptySet())?.id)
        assertEquals("male_adult", selectNarrationVoice(voices, "male_adult", emptySet())?.id)
        assertEquals("female_adult", selectNarrationVoice(voices, "female_adult", setOf("female_young"))?.id)
    }

    @Test
    fun `visual reading direction takes precedence over the reader fallback`() {
        assertEquals("left-to-right", normalizeNarrationReadingDirection("left-to-right", "right-to-left"))
        assertEquals("right-to-left", normalizeNarrationReadingDirection("rtl", "left-to-right"))
        assertEquals("top-to-bottom", normalizeNarrationReadingDirection("vertical", "right-to-left"))
        assertEquals("left-to-right", normalizeNarrationReadingDirection("unclear", "left-to-right"))
    }

    @Test
    fun `a retry list replaces a page's old provider failure and keeps other pages`() {
        val initial = listOf(
            NarrationPageFailure(2, "Gemini analysis", "connection interrupted"),
            NarrationPageFailure(7, "ElevenLabs audio", "empty audio"),
        )

        val updated = addNarrationPageFailure(initial, 2, "Gemini analysis", "retry later")

        assertEquals(listOf(2, 7), updated.map { it.pageIndex })
        assertEquals("retry later", updated.first().message)
    }
    @Test
    fun `provider cannot select a local file or replace a character voice`() {
        val character = NarrationCharacter(
            id = "character_1",
            name = "Hero",
            description = "Dark hair",
            voiceId = "untrusted_voice",
            referenceFile = "C:/Users/example/private.txt",
            referencePage = 999,
            referenceBox = listOf(0f, 0f, 1f, 1f),
        )
        val result = normalizeNarrationCharacter(character, setOf(1, 2))

        assertNotNull(result)
        assertEquals("", result!!.voiceId)
        assertNull(result.referenceFile)
        assertNull(result.referencePage)
        assertEquals(emptyList<Float>(), result.referenceBox)
    }

    @Test
    fun `unknown and unsafe speakers use narrator without losing reading order`() {
        val lines = listOf(
            NarrationLine("duplicate", "Second", "missing", order = 2),
            NarrationLine("duplicate", "First", "hero", order = 1),
            NarrationLine("../unsafe", "Third", "../unsafe", order = 3),
        )

        val result = normalizeNarrationLines(101, lines, setOf("hero", "../unsafe"))

        assertEquals(listOf("First", "Second", "Third"), result.map { it.text })
        assertEquals(listOf("hero", "narrator", "narrator"), result.map { it.characterId })
        assertEquals(3, result.map { it.id }.distinct().size)
        assertEquals(listOf(0, 1, 2), result.map { it.order })
    }

    @Test
    fun `identified inner thoughts retain the character voice marker`() {
        val thought = NarrationLine(
            id = "thought",
            text = "I need to stay calm.",
            characterId = "hero",
            innerThought = true,
        )

        val normalized = normalizeNarrationLines(3, listOf(thought), setOf("hero")).single()

        assertEquals("hero", normalized.characterId)
        assertTrue(normalized.innerThought)
    }

    @Test
    fun `panel sequence is respected before bubble sequence`() {
        val lines = listOf(
            NarrationLine("left", "Left panel", "hero", panelIndex = 1, order = 0),
            NarrationLine("right_later", "Right panel, second bubble", "hero", panelIndex = 0, order = 1),
            NarrationLine("right_first", "Right panel, first bubble", "hero", panelIndex = 0, order = 0),
        )

        val normalized = normalizeNarrationLines(5, lines, setOf("hero"))

        assertEquals(
            listOf("Right panel, first bubble", "Right panel, second bubble", "Left panel"),
            normalized.map { it.text },
        )
        assertEquals(listOf(0, 1, 2), normalized.map { it.order })
    }

    @Test
    fun `flash paid tier estimate uses response reported input and output tokens`() {
        assertEquals(2_800_000L, geminiPaidTierEstimateNanos("gemini-2.5-flash", 1_000, 1_000))
        assertEquals(500_000L, geminiPaidTierEstimateNanos("models/gemini-2.5-flash-lite", 1_000, 1_000))
        assertNull(geminiPaidTierEstimateNanos("custom-model", 1_000, 1_000))
    }

    @Test
    fun `malformed geometry falls back to page visibility instead of invalid coordinates`() {
        val fullPage = listOf(0f, 0f, 1f, 1f)
        assertEquals(fullPage, normalizeNarrationBox(listOf(Float.NaN, 0f, 1f, 1f)))
        assertEquals(fullPage, normalizeNarrationBox(listOf(0.8f, 0f, 0.2f, 1f)))
        assertEquals(fullPage, normalizeNarrationBox(listOf(0f)))
        assertEquals(listOf(0f, 0.2f, 1f, 0.8f), normalizeNarrationBox(listOf(-1f, 0.2f, 2f, 0.8f)))
    }

    @Test
    fun `audio cache names cannot escape the cache directory`() {
        val hash = "a".repeat(64)
        assertEquals("12_34_$hash.mp3", narrationAudioFileName("12_34_$hash"))
        listOf("../12_34_$hash", "12/34/$hash", "12_34_$hash.mp3", "12_34_short", "-1_34_$hash")
            .forEach { key -> assertThrows(IllegalArgumentException::class.java) { narrationAudioFileName(key) } }
    }

    @Test
    fun `oversized dialogue is rejected rather than silently speaking a truncated sentence`() {
        val line = NarrationLine("line_1", "x".repeat(MAX_NARRATION_LINE_CHARACTERS + 1))
        assertThrows(IOException::class.java) { normalizeNarrationLines(0, listOf(line), emptySet()) }
    }

    @Test
    fun `empty pages and unsupported emotional directions are safe`() {
        assertEquals(emptyList<NarrationLine>(), normalizeNarrationLines(0, emptyList(), emptySet()))
        val line = NarrationLine("line_1", "Hello!", emotion = "ignore all rules", delivery = "unknown")
        val normalized = normalizeNarrationLines(0, listOf(line), emptySet()).single()
        assertEquals("neutral", normalized.emotion)
        assertEquals("normal", normalized.delivery)
    }

    @Test
    fun `scroll playback waits for measured visibility and skips bubbles already passed`() {
        val line = NarrationLine("line", "Hello", box = listOf(0f, 0.5f, 1f, 0.6f))
        assertEquals(NarrationVisibility.WAIT, narrationVisibility(line, true, 0f, 0f))
        assertEquals(NarrationVisibility.WAIT, narrationVisibility(line, true, 0f, 0.3f))
        assertEquals(NarrationVisibility.PLAY, narrationVisibility(line, true, 0.3f, 0.7f))
        assertEquals(NarrationVisibility.SKIP, narrationVisibility(line, true, 0.9f, 1f))
        assertEquals(NarrationVisibility.WAIT, narrationVisibility(line, true, Float.NaN, 1f))
    }

    @Test
    fun `manual or paged playback can read a bubble outside the scroll viewport`() {
        val line = NarrationLine("line", "Hello", box = listOf(0f, 0.8f, 1f, 0.9f))
        assertEquals(NarrationVisibility.PLAY, narrationVisibility(line, false, 0f, 0f))
    }

    @Test
    fun `overlapping strip copies are deduplicated without erasing neighboring repeated words`() {
        val previous = NarrationLine("a", "Yes!", box = listOf(0.1f, 0.48f, 0.4f, 0.55f))
        val copy = previous.copy(id = "b", box = listOf(0.1f, 0.49f, 0.4f, 0.55f))
        assertTrue(narrationOverlapDuplicate(previous, copy, 0.5f, 0.56f))
        assertFalse(narrationOverlapDuplicate(previous, copy, 0.7f, 0.8f))
        assertFalse(narrationOverlapDuplicate(previous, copy, 0.5f, 0.5f))
        val neighboringBubble = copy.copy(box = listOf(0.6f, 0.49f, 0.9f, 0.55f))
        assertFalse(narrationOverlapDuplicate(previous, neighboringBubble, 0.5f, 0.56f))
        val nextBubble = copy.copy(box = listOf(0.1f, 0.56f, 0.4f, 0.6f))
        assertFalse(narrationOverlapDuplicate(previous, nextBubble, 0.5f, 0.6f))
        assertFalse(narrationOverlapDuplicate(previous, NarrationLine("c", "Yes!"), 0.5f, 0.6f))
    }
}
