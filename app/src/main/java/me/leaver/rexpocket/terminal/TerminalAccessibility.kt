package me.leaver.rexpocket.terminal

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import me.leaver.rexpocket.core.OutputAnnouncer
import me.leaver.rexpocket.core.ScreenLine
import me.leaver.rexpocket.core.screenLines
import kotlin.math.floor

/**
 * The pane as TalkBack reads it. Each line on screen is a virtual view - rows the terminal wrapped
 * joined into the line the program wrote, blank rows left out - so touching a line reads it and a
 * swipe goes to the next, and a double tap acts as a tap on the pane does (the keyboard, by
 * default). While a line of the pane (or the pane) holds TalkBack's focus, what the program prints
 * is read out politely: only new lines, at most every 1.5 s ([OutputAnnouncer]), and not while a
 * full-screen program (the alternate screen) redraws itself, where touch is the way to read.
 *
 * The keyboard is not this view's: [me.leaver.rexpocket.ui.TerminalImeView] holds it and stays out
 * of TalkBack's way, so typing works as before.
 */
internal class TerminalAccessibility(private val view: TerminalView) : ExploreByTouchHelper(view) {
    private val announcer = OutputAnnouncer()
    private val handler = Handler(Looper.getMainLooper())
    private var lines: List<ScreenLine> = emptyList()
    private var hostFocused = false
    private val bounds = Rect()
    private val speak = Runnable { speakNow() }
    private var refreshing = false
    private val refresh = Runnable {
        refreshing = false
        invalidateRoot()
    }

    private fun enabled(): Boolean =
        view.context.getSystemService(AccessibilityManager::class.java)?.isEnabled == true

    private fun focused(): Boolean = hostFocused || accessibilityFocusedVirtualViewId != INVALID_ID

    /** The lines of [frame] the pane shows, or of what it shows now. */
    fun linesOf(frame: TerminalFrame? = view.screenFrame()): List<ScreenLine> {
        frame ?: return emptyList()
        val rows = view.visibleRows(frame)
        return screenLines(List(rows) { frame.rowText(it) }, List(rows) { frame.wrapped(it) })
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val row = floor(y / view.rowHeight).toInt()
        return linesOf().firstOrNull { row in it.firstRow..it.lastRow }?.firstRow ?: HOST_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        linesOf().mapTo(virtualViewIds) { it.firstRow }
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val line = linesOf().firstOrNull { it.firstRow == virtualViewId }
        val first = line?.firstRow ?: virtualViewId.coerceAtLeast(0)
        val last = line?.lastRow ?: first
        node.className = "android.widget.TextView"
        node.text = line?.text.orEmpty()
        val top = (first * view.rowHeight).toInt().coerceIn(0, (view.height - 1).coerceAtLeast(0))
        val bottom = ((last + 1) * view.rowHeight).toInt().coerceIn(top + 1, view.height.coerceAtLeast(top + 1))
        bounds.set(0, top, view.width.coerceAtLeast(1), bottom)
        @Suppress("DEPRECATION")
        node.setBoundsInParent(bounds)
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        super.onPopulateNodeForHost(node)
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        view.performClick()
        return true
    }

    override fun sendAccessibilityEvent(host: View, eventType: Int) {
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> hostFocused = true
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> hostFocused = false
        }
        super.sendAccessibilityEvent(host, eventType)
    }

    /** The pane drew [frame]: TalkBack's lines follow it, and new output is read out while it listens. */
    fun screenChanged(frame: TerminalFrame?) {
        if (!enabled()) {
            if (announcer.listening) announcer.stop()
            return
        }
        val now = linesOf(frame)
        if (now == lines) return
        val before = lines
        lines = now
        if (!refreshing) {
            refreshing = true
            handler.postDelayed(refresh, REFRESH_MS)
        }
        if (!focused() || frame == null || frame.alternateScreen) {
            announcer.stop()
            return
        }
        // What was on screen when the focus came is not news; only what came since.
        if (!announcer.listening) announcer.start(before.map { it.text })
        val cursor = now.indexOfFirst { frame.cursorY in it.firstRow..it.lastRow }
        announcer.changed(now.map { it.text }, cursor)
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(speak)
        val at = announcer.dueAt() ?: return
        handler.postDelayed(speak, (at - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun speakNow() {
        val text = announcer.take(SystemClock.uptimeMillis())
        if (text != null && focused()) {
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        }
        schedule()
    }

    /** The pane left the window: nothing more is said for it. */
    fun release() {
        handler.removeCallbacks(speak)
        handler.removeCallbacks(refresh)
        refreshing = false
        announcer.stop()
        hostFocused = false
    }

    private companion object {
        /** How often TalkBack is told the lines changed while a program writes. */
        const val REFRESH_MS = 300L
    }
}
