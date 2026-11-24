package me.leaver.rexpocket.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// The terminal's text size, as a share of its 12 sp base: the default for new tabs (Settings ›
// Appearance, and the Terminal font dialog) and each tab's own, which a pinch, the ⋮ menu's Text
// size row, the palette and Ctrl+= / Ctrl+- / Ctrl+0 change. See terminal-controls.md, "Text size".

object TextScale {
    const val MIN = 0.7f
    const val MAX = 1.8f

    /** What − and + change it by. */
    const val STEP = 0.1f

    /** What the app started with: 12 sp. */
    const val DEFAULT = 1f

    fun clamp(scale: Float): Float = scale.coerceIn(MIN, MAX)

    /** The next step up: a size a pinch left between steps goes to the step above it. */
    fun bigger(scale: Float): Float = clamp((floor(scale / STEP + EPSILON) + 1) * STEP)

    /** The next step down. */
    fun smaller(scale: Float): Float = clamp((ceil(scale / STEP - EPSILON) - 1) * STEP)

    fun percent(scale: Float): Int = (scale * 100).roundToInt()

    fun same(a: Float, b: Float): Boolean = abs(a - b) < 0.005f

    private const val EPSILON = 0.001f
}

/**
 * Each tab's own text size, where it differs from the default: by host and tab. A tab not in it
 * shows the default, so a new tab, and every tab after Reset, follows Settings. Kept while the app
 * runs, not stored: Rex's tab ids outlive nothing on the phone that would prune them.
 */
data class TabTextSizes(val sizes: Map<String, Float> = emptyMap()) {
    fun of(key: String, default: Float): Float = sizes[key] ?: default

    /** [key]'s size made [scale]; one equal to [default] is no override at all. */
    fun with(key: String, scale: Float, default: Float): TabTextSizes {
        val next = TextScale.clamp(scale)
        return if (TextScale.same(next, default)) reset(key) else copy(sizes = sizes + (key to next))
    }

    fun reset(key: String): TabTextSizes = if (key in sizes) copy(sizes = sizes - key) else this

    companion object {
        fun key(hostId: String?, sessionId: String, windowId: String) = "${hostId.orEmpty()}/$sessionId/$windowId"
    }
}
