package me.leaver.rexpocket.core

import kotlin.math.hypot

// What a tap, a double tap and a long press on the terminal do, and how long each one takes. The
// person picks them in Settings › Terminal; the defaults are what the app always did.

/**
 * A single tap on the pane. A program that reads the mouse gets its click either way. [Keyboard]
 * raises the keyboard, and in full screen, where the key bar and its keyboard key are gone, it puts
 * it away again too; [Toggle] does that everywhere.
 */
enum class TapAction(val pref: String) {
    Keyboard("keyboard"),
    Toggle("toggle"),
    Nothing("nothing");

    companion object {
        fun fromPref(value: String?): TapAction = entries.firstOrNull { it.pref == value } ?: Keyboard
    }
}

/** A second tap soon after a first. [Nothing] leaves every tap a tap, and none of them waits. */
enum class DoubleTapAction(val pref: String) {
    FullScreen("full_screen"),
    Keyboard("keyboard"),
    Nothing("nothing");

    companion object {
        fun fromPref(value: String?): DoubleTapAction? = entries.firstOrNull { it.pref == value }
    }
}

/** A press held still. A link under the finger is always offered, whatever is chosen here. */
enum class LongPressAction(val pref: String) {
    Select("select"),
    FullScreen("full_screen"),
    Keyboard("keyboard");

    companion object {
        fun fromPref(value: String?): LongPressAction = entries.firstOrNull { it.pref == value } ?: Select
    }
}

/**
 * The terminal's gestures as the person set them. [doubleTapMs] and [longPressMs] of 0 mean the
 * phone's own timeouts, which is what they are until changed.
 */
data class GestureSettings(
    val tap: TapAction = TapAction.Keyboard,
    val doubleTap: DoubleTapAction = DoubleTapAction.FullScreen,
    val longPress: LongPressAction = LongPressAction.Select,
    /** True when a single tap acts only once the time for a second has passed. */
    val waitForDoubleTap: Boolean = false,
    val doubleTapMs: Int = 0,
    val longPressMs: Int = 0,
) {
    /** The timing to use on a phone whose own timeouts are [systemDoubleTapMs] and [systemLongPressMs]. */
    fun timing(systemDoubleTapMs: Int, systemLongPressMs: Int, touchSlop: Float, doubleTapSlop: Float) = TapTiming(
        doubleTaps = doubleTap != DoubleTapAction.Nothing,
        waitForDoubleTap = waitForDoubleTap,
        doubleTapMs = (doubleTapMs.takeIf { it > 0 } ?: systemDoubleTapMs).toLong(),
        longPressMs = (longPressMs.takeIf { it > 0 } ?: systemLongPressMs).toLong(),
        touchSlop = touchSlop,
        doubleTapSlop = doubleTapSlop,
    )

    companion object {
        /**
         * The ranges the settings offer, a keyboard app's kind of range. The floors are as quick as
         * a deliberate finger gets: a double tap's two taps 100 ms apart, a press held 150 ms. The
         * phone's own times (about 300 and 400 ms) sit well inside both.
         */
        val DOUBLE_TAP_RANGE = 100..600
        val LONG_PRESS_RANGE = 150..1500

        /**
         * The settings as stored. A double tap with no stored choice takes the one of the switch that
         * came before it, [legacyDoubleTapFullScreen]: off was "nothing". Unknown values fall back to
         * the defaults, and times outside their range are brought into it; 0 stays the phone's own.
         */
        fun fromStored(
            tap: String?,
            doubleTap: String?,
            longPress: String?,
            waitForDoubleTap: Boolean,
            doubleTapMs: Int,
            longPressMs: Int,
            legacyDoubleTapFullScreen: Boolean?,
        ) = GestureSettings(
            tap = TapAction.fromPref(tap),
            doubleTap = DoubleTapAction.fromPref(doubleTap)
                ?: if (legacyDoubleTapFullScreen == false) DoubleTapAction.Nothing else DoubleTapAction.FullScreen,
            longPress = LongPressAction.fromPref(longPress),
            waitForDoubleTap = waitForDoubleTap,
            doubleTapMs = if (doubleTapMs <= 0) 0 else doubleTapMs.coerceIn(DOUBLE_TAP_RANGE),
            longPressMs = if (longPressMs <= 0) 0 else longPressMs.coerceIn(LONG_PRESS_RANGE),
        )
    }
}

/** How the pane tells its gestures apart. Times in milliseconds, distances in pixels. */
data class TapTiming(
    /** False when a double tap does nothing: every tap is then a tap, at once. */
    val doubleTaps: Boolean = true,
    val waitForDoubleTap: Boolean = false,
    val doubleTapMs: Long = 300,
    val longPressMs: Long = 400,
    /** How far a finger may move and still be tapping, not dragging. */
    val touchSlop: Float = 16f,
    /** How far from the first tap the second may land and still make a double tap. */
    val doubleTapSlop: Float = 100f,
)

/** What a finger did on the pane, where it was. */
sealed interface Gesture {
    data class Tap(val x: Float, val y: Float) : Gesture

    /** [firstTapActed] is true when the first of the two taps already went out as a [Tap]. */
    data class DoubleTap(val x: Float, val y: Float, val firstTapActed: Boolean) : Gesture

    data class LongPress(val x: Float, val y: Float) : Gesture
}

/**
 * Tells a tap, a double tap and a long press apart, from touches and the time. It knows no view
 * and no clock: each call gives the time, and [deadline] says when [timer] has to be called next
 * for a gesture that ends by time passing - a press held long enough, or a tap that waited for a
 * second one that did not come.
 *
 * - A press held still for [TapTiming.longPressMs] is a long press, and that press is then over.
 * - A press that moves further than [TapTiming.touchSlop], or is cancelled, is nothing here: it is a
 *   drag, a swipe or a pinch, which the view handles itself.
 * - A second press that lands within [TapTiming.doubleTapMs] of the first tap lifting, and near it,
 *   is a double tap when it lifts. Held into a long press, or dragged, it is that instead, and a
 *   first tap that was waiting for it is dropped: the person was not just tapping.
 * - Without [TapTiming.waitForDoubleTap], a tap acts as it lifts; the double tap then follows it.
 *   With it, a tap acts once the time for a second one has passed, so the first tap of a double tap
 *   never acts on its own. With double taps off, every tap acts at once.
 */
class TapGestures(var timing: TapTiming = TapTiming()) {
    private sealed interface State {
        data object Idle : State

        /** Down and still; [second] when this press may be a double tap's second. */
        data class Pressed(val at: Long, val x: Float, val y: Float, val second: Boolean, val firstActed: Boolean) : State

        /** A tap lifted at [at]; [acted] when it already went out, false while it waits. */
        data class Tapped(val at: Long, val x: Float, val y: Float, val acted: Boolean) : State

        /** The press became something else; nothing more until the next one. */
        data object Spent : State
    }

    private var state: State = State.Idle

    /** When [timer] must be called next, or null when nothing ends by time. */
    val deadline: Long?
        get() = when (val s = state) {
            is State.Pressed -> s.at + timing.longPressMs
            is State.Tapped -> s.at + timing.doubleTapMs
            else -> null
        }

    fun down(t: Long, x: Float, y: Float): List<Gesture> {
        val out = expire(t).toMutableList()
        val s = state
        state = if (s is State.Tapped && timing.doubleTaps && hypot(x - s.x, y - s.y) <= timing.doubleTapSlop) {
            State.Pressed(t, x, y, second = true, firstActed = s.acted)
        } else {
            // A press somewhere else is no second tap: a tap still waiting is a tap after all.
            if (s is State.Tapped && !s.acted) out += Gesture.Tap(s.x, s.y)
            State.Pressed(t, x, y, second = false, firstActed = false)
        }
        return out
    }

    fun move(t: Long, x: Float, y: Float): List<Gesture> {
        val out = expire(t)
        val s = state
        if (s is State.Pressed && hypot(x - s.x, y - s.y) > timing.touchSlop) state = State.Spent
        return out
    }

    fun up(t: Long, x: Float, y: Float): List<Gesture> {
        val out = expire(t).toMutableList()
        val s = state
        state = State.Idle
        if (s !is State.Pressed) return out
        when {
            s.second -> out += Gesture.DoubleTap(x, y, s.firstActed)
            !timing.doubleTaps -> out += Gesture.Tap(x, y)
            timing.waitForDoubleTap -> state = State.Tapped(t, x, y, acted = false)
            else -> {
                out += Gesture.Tap(x, y)
                state = State.Tapped(t, x, y, acted = true)
            }
        }
        return out
    }

    /** A second finger, a pinch, or the system taking the touch: the press is nothing. */
    fun cancel() {
        state = State.Idle
    }

    /** Time has passed to [t]: a press held long enough, or a waiting tap whose time is up. */
    fun timer(t: Long): List<Gesture> = expire(t)

    private fun expire(t: Long): List<Gesture> {
        when (val s = state) {
            is State.Pressed -> if (t - s.at >= timing.longPressMs) {
                state = State.Spent
                return listOf(Gesture.LongPress(s.x, s.y))
            }
            is State.Tapped -> if (t - s.at > timing.doubleTapMs) {
                state = State.Idle
                return if (s.acted) emptyList() else listOf(Gesture.Tap(s.x, s.y))
            }
            else -> Unit
        }
        return emptyList()
    }
}

/**
 * What a single tap does to the keyboard, which is [up] now: true to raise it, false to put it
 * away, null to leave it. In full screen there is no keyboard key, so the tap that opens the
 * keyboard closes it as well - nobody is left with a keyboard and no way to put it down.
 */
fun keyboardAfterTap(action: TapAction, up: Boolean, fullScreen: Boolean): Boolean? = when (action) {
    TapAction.Keyboard -> if (fullScreen) !up else true
    TapAction.Toggle -> !up
    TapAction.Nothing -> null
}

/**
 * Whether the keyboard should be up after a double tap. [before] is how it was before the gesture
 * began: a first tap that acted at once may have raised it, and the double tap undoes that. Full
 * screen leaves it as it was; the keyboard action turns it the other way.
 */
fun keyboardAfterDoubleTap(before: Boolean, action: DoubleTapAction): Boolean = when (action) {
    DoubleTapAction.Keyboard -> !before
    DoubleTapAction.FullScreen, DoubleTapAction.Nothing -> before
}
