package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextScaleTest {
    @Test
    fun stepsLandOnTheGridAndStopAtTheEnds() {
        assertEquals(1.1f, TextScale.bigger(1f), 0.001f)
        assertEquals(0.9f, TextScale.smaller(1f), 0.001f)
        // A pinch leaves sizes between steps: + and − go to the next step, not a step further.
        assertEquals(1.1f, TextScale.bigger(1.04f), 0.001f)
        assertEquals(1.0f, TextScale.smaller(1.04f), 0.001f)
        assertEquals(TextScale.MAX, TextScale.bigger(TextScale.MAX), 0.001f)
        assertEquals(TextScale.MIN, TextScale.smaller(TextScale.MIN), 0.001f)
        assertEquals(TextScale.MAX, TextScale.clamp(5f), 0.001f)
        assertEquals(110, TextScale.percent(1.1f))
    }

    @Test
    fun aTabHasItsOwnSizeUntilResetAndOthersFollowTheDefault() {
        val a = TabTextSizes.key("h", "s", "a")
        val b = TabTextSizes.key("h", "s", "b")
        var sizes = TabTextSizes()
        assertEquals(1.2f, sizes.of(a, 1.2f), 0.001f)
        sizes = sizes.with(a, 0.8f, default = 1.2f)
        assertEquals(0.8f, sizes.of(a, 1.2f), 0.001f)
        assertEquals("another tab keeps the default", 1.2f, sizes.of(b, 1.2f), 0.001f)
        assertEquals("the default changes for tabs that follow it", 1.5f, sizes.of(b, 1.5f), 0.001f)
        // Back at the default is no override, so a later default applies to it too.
        assertEquals(emptyMap<String, Float>(), sizes.with(a, 1.2f, default = 1.2f).sizes)
        assertEquals(emptyMap<String, Float>(), sizes.reset(a).sizes)
        assertEquals(TextScale.MAX, sizes.with(a, 9f, 1f).of(a, 1f), 0.001f)
    }
}
