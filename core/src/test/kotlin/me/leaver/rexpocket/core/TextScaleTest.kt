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

    /**
     * One range everywhere, the pinch's: 6 to 40 sp of the 12 sp base. A size pinched to 250% is
     * 250%, and + and − step from it to 260% and 240%, never back into a narrower range.
     */
    @Test
    fun theRangeIsThePinchsAndAStepNeverNarrowsIt() {
        assertEquals(6f, TextScale.MIN * 12f, 0.001f)
        assertEquals(40f, TextScale.MAX * 12f, 0.001f)
        val pinched = 30f / 12f
        assertEquals(250, TextScale.percent(pinched))
        assertEquals(2.5f, TextScale.clamp(pinched), 0.001f)
        assertEquals(2.6f, TextScale.bigger(pinched), 0.001f)
        assertEquals(2.4f, TextScale.smaller(pinched), 0.001f)
        val key = TabTextSizes.key("h", "s", "a")
        assertEquals(2.5f, TabTextSizes().with(key, pinched, 1f).of(key, 1f), 0.001f)
        // The ends are the pinch's ends, and a step past them stays there.
        assertEquals(0.5f, TextScale.smaller(0.6f), 0.001f)
        assertEquals(TextScale.MAX, TextScale.bigger(3.3f), 0.001f)
        assertEquals(333, TextScale.percent(TextScale.MAX))
    }

    /** A backup takes any size the app can show, a pinched 250% too. */
    @Test
    fun aBackupTakesTheWholeRange() {
        val text = org.json.JSONObject().put("format", SettingsFormat.FORMAT)
            .put("terminal", org.json.JSONObject().put("fontScale", 2.5)).toString()
        when (val p = SettingsFormat.parse(text)) {
            is SettingsParse.Parsed -> assertEquals(2.5f, p.backup.terminalFontScale!!, 0.001f)
            is SettingsParse.Failed -> throw AssertionError("refused: ${p.problem}")
        }
    }
}
