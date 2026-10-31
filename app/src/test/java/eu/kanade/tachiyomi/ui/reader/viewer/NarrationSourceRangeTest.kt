package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NarrationSourceRangeTest {

    @Test
    fun `normalizes a visible source band`() {
        assertEquals(0.2f to 0.6f, narrationSourceRange(200f, 600f, 1000))
    }

    @Test
    fun `clips a partially visible band to the image`() {
        assertEquals(0f to 0.5f, narrationSourceRange(-200f, 500f, 1000))
        assertEquals(0.7f to 1f, narrationSourceRange(700f, 1400f, 1000))
    }

    @Test
    fun `does not narrate image padding or an inverted band`() {
        assertNull(narrationSourceRange(-200f, -10f, 1000))
        assertNull(narrationSourceRange(1100f, 1200f, 1000))
        assertNull(narrationSourceRange(600f, 200f, 1000))
    }

    @Test
    fun `ignores unavailable or nonfinite image geometry`() {
        assertNull(narrationSourceRange(0f, 200f, 0))
        assertNull(narrationSourceRange(Float.NaN, 200f, 1000))
        assertNull(narrationSourceRange(0f, Float.POSITIVE_INFINITY, 1000))
    }
}
