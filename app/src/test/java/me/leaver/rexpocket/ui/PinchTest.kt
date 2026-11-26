package me.leaver.rexpocket.ui

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * A pinch scales the pane's picture while the fingers move and sets the text size once, as they
 * lift: each size set refits the grid, a reflow of the whole history and a resize for the program.
 */
@RunWith(AndroidJUnit4::class)
class PinchTest {
    @Test
    fun aPinchSetsTheSizeOnceAtTheEnd() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TerminalView(activity)
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 1600)
        val sizes = mutableListOf<Float>()
        view.onFontSizeChanged = { sizes += it }
        val start = view.fontSizeSp

        val down = SystemClock.uptimeMillis()
        var t = down
        fun event(action: Int, gap: Float, pointers: Int): MotionEvent {
            val props = Array(pointers) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(pointers) { i -> MotionEvent.PointerCoords().apply { x = 500f + (if (i == 0) -gap else gap); y = 800f; pressure = 1f; size = 1f } }
            return MotionEvent.obtain(down, t, action, pointers, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 1))
        t += 10
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 2))
        var gap = 100f
        repeat(30) {
            t += 16
            gap += 10f
            view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, gap, 2))
        }
        assertEquals("no size set while the fingers move", emptyList<Float>(), sizes)
        assertEquals(start, view.fontSizeSp, 0.001f)
        t += 16
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), gap, 2))
        t += 16
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, gap, 1))

        assertEquals("one size, set as the fingers lift: $sizes", 1, sizes.size)
        assertTrue("larger: ${sizes.single()} from $start", sizes.single() > start * 1.5f)
        assertEquals(sizes.single(), view.fontSizeSp, 0.001f)
    }
}
