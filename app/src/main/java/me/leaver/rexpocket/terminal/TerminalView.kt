package me.leaver.rexpocket.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [TerminalSession]'s screen and turns touches and keys into what its program reads.
 *
 * Everything on screen comes from libghostty-vt's render state ([TerminalFrame]); this view only
 * decides how a cell looks and what a gesture means: a drag scrolls the history - or, for a program
 * that asked for the mouse or that has the alternate screen, scrolls the program; a tap clicks for
 * a program that wants the mouse; a long press selects a word or opens the link under the finger;
 * two fingers change the font size.
 */
internal class TerminalView(context: Context) : View(context) {
    var session: TerminalSession? = null
        set(value) {
            if (field === value) return
            clearSelection()
            field?.let {
                it.onChanged = null
                it.selection = null
            }
            field = value
            value?.let {
                it.onChanged = { postInvalidateOnAnimation() }
                it.selection = { selection?.text().orEmpty() }
                it.theme = theme
            }
            fitGrid()
            invalidate()
        }

    var theme: TerminalTheme = TerminalTheme(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF3366FF.toInt(), 0xFFCCCCCC.toInt())
        set(value) {
            if (field == value) return
            field = value
            session?.theme = value
            invalidate()
        }

    var typeface: Typeface = Typeface.MONOSPACE
        set(value) {
            if (field === value) return
            field = value
            measureFont()
        }

    var fontSizeSp: Float = 12f
        set(value) {
            val next = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (abs(field - next) < 0.01f) return
            field = next
            measureFont()
        }

    /** The size the user pinched to, in sp. */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** A web link the user long-pressed. */
    var onLink: ((String) -> Unit)? = null

    /** A tap that was not the start of anything else. */
    var onTap: (() -> Unit)? = null

    /** A horizontal swipe; true when it went towards the next pane. */
    var onSwipe: ((forward: Boolean) -> Unit)? = null

    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val bold = Paint(regular)
    private val italic = Paint(regular)
    private val boldItalic = Paint(regular)
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f

    /** What the pane drew last, for the moment a session is being replaced and has nothing yet. */
    private var lastFrame: TerminalFrame? = null
    private var composing = ""
    private var selection: TerminalSelection? = null
    private var selectionMode: ActionMode? = null
    /** Which end of the selection a finger holds: its anchor, or the end that moves. */
    private var grabbedAnchor = false
    private var selectionDrag = false
    private var lastScrollAt = 0L
    private val glyphs = CharArray(512)

    // Touch
    private val scroller = OverScroller(context)
    private var flingLast = 0
    private var scrollRemainder = 0f
    private var axis = Axis.None
    private var downX = 0f
    private var downY = 0f
    private var touchColumn = 0
    private var touchRow = 0
    private val swipeThreshold = SWIPE_DP * density
    private val handled = HashSet<Int>()

    private enum class Axis { None, Vertical, Horizontal }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                scrollRemainder = 0f
                axis = Axis.None
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (axis == Axis.None && e1 != null) {
                    val dx = abs(e2.x - e1.x)
                    val dy = abs(e2.y - e1.y)
                    axis = if (dx > dy * HORIZONTAL_RATIO) Axis.Horizontal else Axis.Vertical
                }
                if (axis == Axis.Vertical) scrollByPixels(distanceY, e2.x, e2.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // A fling over a program that reads the mouse or arrow keys would flood it with input.
                val frame = currentFrame() ?: return false
                if (axis != Axis.Vertical || frame.mouseTracking || frame.alternateScreen) return false
                flingLast = 0
                scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, -FLING_RANGE, FLING_RANGE)
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val frame = currentFrame()
                val active = session
                if (frame != null && active != null && frame.mouseTracking && frame.atBottom) {
                    val (column, row) = cellAt(e.x, e.y)
                    active.sendRaw(active.encodeClick(column, row, cellWidth.roundToInt(), cellHeight.roundToInt()))
                }
                onTap?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) = longPress(e)
        },
    )

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (fontSizeSp * detector.scaleFactor).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
                // A change too small to move a cell is kept for the next event, which a refusal does.
                if (abs(target - fontSizeSp) < PINCH_STEP_SP) return false
                fontSizeSp = target
                onFontSizeChanged?.invoke(target)
                return true
            }
        },
    )

    init {
        // Typing goes through TerminalImeView, which sits under this view: its own input connection
        // would answer the keyboard with no text type at all.
        isFocusable = false
        isFocusableInTouchMode = false
        isLongClickable = true
        measureFont()
    }

    // ---- Layout ----------------------------------------------------------------------------

    private fun measureFont() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        regular.typeface = typeface
        bold.typeface = Typeface.create(typeface, Typeface.BOLD)
        italic.typeface = Typeface.create(typeface, Typeface.ITALIC)
        boldItalic.typeface = Typeface.create(typeface, Typeface.BOLD_ITALIC)
        for (paint in listOf(regular, bold, italic, boldItalic)) paint.textSize = px
        cellWidth = regular.measureText("M").coerceAtLeast(1f)
        val metrics = regular.fontMetrics
        cellHeight = ceil(metrics.descent - metrics.ascent).coerceAtLeast(1f)
        baseline = -metrics.ascent
        clearSelection()
        fitGrid()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearSelection()
        fitGrid()
    }

    private fun fitGrid() {
        val active = session ?: return
        if (width <= 0 || height <= 0) return
        val columns = max(1, floor(width / cellWidth).toInt())
        val rows = max(1, floor(height / cellHeight).toInt())
        active.resize(columns, rows, cellWidth.roundToInt(), cellHeight.roundToInt())
    }

    private fun currentFrame(): TerminalFrame? {
        val frame = session?.frame()
        if (frame != null) lastFrame = frame
        return frame ?: lastFrame
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val frame = currentFrame()
        val column = floor(x / cellWidth).toInt().coerceIn(0, (frame?.columns ?: 1) - 1)
        val row = floor(y / cellHeight).toInt().coerceIn(0, (frame?.rows ?: 1) - 1)
        return column to row
    }

    // ---- Drawing ---------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val frame = selection?.frame ?: currentFrame()
        canvas.drawColor(frame?.background ?: theme.background)
        if (frame == null) return
        val columns = min(frame.columns, ceil(width / cellWidth).toInt())
        val rows = min(frame.rows, ceil(height / cellHeight).toInt())
        for (row in 0 until rows) drawBackgrounds(canvas, frame, row, columns)
        for (row in 0 until rows) drawText(canvas, frame, row, columns)
        if (selection == null) drawCursor(canvas, frame)
        drawHandles(canvas)
        drawScrollbar(canvas, frame)
    }

    private fun selected(column: Int, row: Int): Boolean {
        val s = selection ?: return false
        val from = s.first
        val to = s.last
        val afterStart = row > from.row || (row == from.row && column >= from.column)
        val beforeEnd = row < to.row || (row == to.row && column < s.endColumn(to))
        return afterStart && beforeEnd
    }

    private fun x(column: Int): Float = column * cellWidth

    private fun drawBackgrounds(canvas: Canvas, frame: TerminalFrame, row: Int, columns: Int) {
        val top = row * cellHeight
        var start = 0
        var end = 0
        var color = 0
        var open = false
        fun flush() {
            if (!open) return
            fill.color = color
            canvas.drawRect(x(start).roundToInt().toFloat(), top, x(end).roundToInt().toFloat(), top + cellHeight, fill)
            open = false
        }
        for (column in 0 until columns) {
            val i = frame.index(column, row)
            val next: Int? = when {
                selected(column, row) -> theme.selection
                frame.flags[i] and TerminalFrame.FLAG_INVERSE != 0 -> frame.fg[i]
                frame.flags[i] and TerminalFrame.FLAG_BG_DEFAULT != 0 -> null
                else -> frame.bg[i]
            }
            if (next == null) {
                flush()
            } else if (open && next == color && column == end) {
                end = column + 1
            } else {
                flush()
                start = column
                end = column + 1
                color = next
                open = true
            }
        }
        flush()
    }

    private fun paintFor(flags: Int): Paint = when {
        flags and TerminalFrame.FLAG_BOLD != 0 && flags and TerminalFrame.FLAG_ITALIC != 0 -> boldItalic
        flags and TerminalFrame.FLAG_BOLD != 0 -> bold
        flags and TerminalFrame.FLAG_ITALIC != 0 -> italic
        else -> regular
    }

    /** The colour a cell's text is drawn in, with what it is drawn on already settled. */
    private fun textColor(frame: TerminalFrame, i: Int, column: Int, row: Int): Int = when {
        selected(column, row) -> theme.foreground
        frame.flags[i] and TerminalFrame.FLAG_INVERSE != 0 -> frame.bg[i]
        else -> frame.fg[i]
    }

    private fun drawText(canvas: Canvas, frame: TerminalFrame, row: Int, columns: Int) {
        val y = row * cellHeight + baseline
        var runStart = -1
        var runLength = 0
        var runKey = 0
        var runColor = 0
        fun flush() {
            if (runLength == 0) return
            val paint = paintFor(runKey)
            paint.color = runColor
            paint.alpha = if (runKey and TerminalFrame.FLAG_FAINT != 0) FAINT_ALPHA else 255
            canvas.drawText(glyphs, 0, runLength, x(runStart), y, paint)
            runLength = 0
        }
        for (column in 0 until columns) {
            val i = frame.index(column, row)
            val width = frame.width(i)
            if (width == 0) continue
            val flags = frame.flags[i]
            val color = textColor(frame, i, column, row)
            val visible = flags and TerminalFrame.FLAG_INVISIBLE == 0 && !frame.isBlank(i)
            val point = frame.singleCodePoint(i)
            if (visible && width == 1 && point in ' '.code + 1..'~'.code) {
                val key = flags and (TerminalFrame.FLAG_BOLD or TerminalFrame.FLAG_ITALIC or TerminalFrame.FLAG_FAINT)
                val continues = runLength in 1 until glyphs.size && key == runKey && color == runColor && column == runStart + runLength
                if (!continues) {
                    flush()
                    runStart = column
                    runKey = key
                    runColor = color
                }
                glyphs[runLength++] = point.toChar()
            } else {
                flush()
                if (visible) drawGlyph(canvas, frame, i, column, row, color)
            }
            drawDecorations(canvas, flags, color, column, width, row)
        }
        flush()
    }

    /** One cell's text, squeezed into the cell if the font made it wider than the cell allows. */
    private fun drawGlyph(canvas: Canvas, frame: TerminalFrame, i: Int, column: Int, row: Int, color: Int) {
        val flags = frame.flags[i]
        val paint = paintFor(flags)
        paint.color = color
        paint.alpha = if (flags and TerminalFrame.FLAG_FAINT != 0) FAINT_ALPHA else 255
        val text = frame.cellText(i)
        val left = x(column)
        val y = row * cellHeight + baseline
        val allowed = max(1, frame.width(i)) * cellWidth
        val advance = paint.measureText(text)
        if (advance > allowed * 1.02f) {
            canvas.save()
            canvas.clipRect(left, row * cellHeight, left + allowed, (row + 1) * cellHeight)
            canvas.scale(allowed / advance, 1f, left, y)
            canvas.drawText(text, left, y, paint)
            canvas.restore()
        } else {
            canvas.drawText(text, left, y, paint)
        }
    }

    private fun drawDecorations(canvas: Canvas, flags: Int, color: Int, column: Int, width: Int, row: Int) {
        if (flags and (TerminalFrame.FLAG_UNDERLINE or TerminalFrame.FLAG_STRIKETHROUGH) == 0) return
        stroke.color = color
        stroke.strokeWidth = density.coerceAtLeast(1f)
        val left = x(column)
        val right = left + width * cellWidth
        val top = row * cellHeight
        if (flags and TerminalFrame.FLAG_UNDERLINE != 0) canvas.drawLine(left, top + baseline + 2f, right, top + baseline + 2f, stroke)
        if (flags and TerminalFrame.FLAG_STRIKETHROUGH != 0) canvas.drawLine(left, top + cellHeight / 2, right, top + cellHeight / 2, stroke)
    }

    private fun drawCursor(canvas: Canvas, frame: TerminalFrame) {
        if (frame.cursorX < 0 || frame.cursorY < 0 || frame.cursorX >= frame.columns || frame.cursorY >= frame.rows) return
        val i = frame.index(frame.cursorX, frame.cursorY)
        val span = max(1, frame.width(i))
        val left = x(frame.cursorX)
        val top = frame.cursorY * cellHeight
        val right = left + span * cellWidth
        val bottom = top + cellHeight
        fill.color = theme.cursor
        stroke.color = theme.cursor
        stroke.strokeWidth = density.coerceAtLeast(1f)
        when (frame.cursorStyle) {
            TerminalFrame.CURSOR_BAR -> canvas.drawRect(left, top, left + 2 * density, bottom, fill)
            TerminalFrame.CURSOR_UNDERLINE -> canvas.drawRect(left, bottom - 2 * density, right, bottom, fill)
            TerminalFrame.CURSOR_HOLLOW -> {
                stroke.style = Paint.Style.STROKE
                canvas.drawRect(left + 0.5f, top + 0.5f, right - 0.5f, bottom - 0.5f, stroke)
                stroke.style = Paint.Style.FILL
            }
            else -> {
                canvas.drawRect(left, top, right, bottom, fill)
                if (!frame.isBlank(i) && frame.flags[i] and TerminalFrame.FLAG_INVISIBLE == 0) {
                    drawGlyph(canvas, frame, i, frame.cursorX, frame.cursorY, frame.background)
                }
            }
        }
        if (composing.isNotEmpty()) drawComposing(canvas, left, top)
    }

    /** What the keyboard is still composing, over the cursor and underlined: it is not input yet. */
    private fun drawComposing(canvas: Canvas, left: Float, top: Float) {
        val width = max(cellWidth, regular.measureText(composing))
        fill.color = theme.background
        canvas.drawRect(left, top, left + width, top + cellHeight, fill)
        regular.color = theme.foreground
        regular.alpha = 255
        canvas.drawText(composing, left, top + baseline, regular)
        stroke.color = theme.foreground
        stroke.strokeWidth = density.coerceAtLeast(1f)
        canvas.drawLine(left, top + baseline + 2f, left + width, top + baseline + 2f, stroke)
    }

    private fun drawHandles(canvas: Canvas) {
        val s = selection ?: return
        fill.color = theme.cursor
        val radius = 5 * density
        canvas.drawCircle(x(s.first.column), (s.first.row + 1) * cellHeight, radius, fill)
        canvas.drawCircle(x(s.endColumn(s.last)), (s.last.row + 1) * cellHeight, radius, fill)
    }

    private fun drawScrollbar(canvas: Canvas, frame: TerminalFrame) {
        val age = SystemClock.uptimeMillis() - lastScrollAt
        if (age > SCROLLBAR_MS || frame.scrollTotal <= frame.scrollVisible) return
        val trackHeight = height.toFloat()
        val barHeight = max(24 * density, trackHeight * frame.scrollVisible / frame.scrollTotal)
        val barTop = (trackHeight - barHeight) * frame.scrollOffset / (frame.scrollTotal - frame.scrollVisible)
        fill.color = (theme.foreground and 0xFFFFFF) or ((120 * (1f - age / SCROLLBAR_MS.toFloat())).toInt() shl 24)
        val bar = RectF(width - 4 * density, barTop, width - 1 * density, barTop + barHeight)
        canvas.drawRoundRect(bar, 2 * density, 2 * density, fill)
        postInvalidateOnAnimation()
    }

    // ---- Scrolling -------------------------------------------------------------------------

    private fun scrollByPixels(pixels: Float, x: Float, y: Float) {
        scrollRemainder += pixels
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val (column, row) = cellAt(x, y)
        scrollLines(lines, column, row)
    }

    /** Rows towards newer output when positive; what that means depends on what the program asked for. */
    private fun scrollLines(lines: Int, column: Int, row: Int) {
        val active = session ?: return
        val frame = currentFrame() ?: return
        val steps = min(abs(lines), MAX_STEPS_PER_MOVE)
        val down = lines > 0
        when {
            frame.mouseTracking -> repeat(steps) {
                active.sendRaw(active.encodeWheel(down, column, row, cellWidth.roundToInt(), cellHeight.roundToInt()))
            }
            // The alternate screen has no history; the program takes the wheel as the arrow keys.
            frame.alternateScreen && frame.alternateScroll -> {
                val key = if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                repeat(steps) { active.encodeKey(key, false, 0, null)?.let(active::sendRaw) }
            }
            else -> {
                active.scroll(lines)
                lastScrollAt = SystemClock.uptimeMillis()
            }
        }
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val y = scroller.currY
        scrollByPixels((y - flingLast).toFloat(), touchX, touchY)
        flingLast = y
        postInvalidateOnAnimation()
    }

    private var touchX = 0f
    private var touchY = 0f

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && selection == null) {
            val lines = (-event.getAxisValue(MotionEvent.AXIS_VSCROLL) * WHEEL_LINES).roundToInt()
            if (lines != 0) {
                val (column, row) = cellAt(event.x, event.y)
                scrollLines(lines, column, row)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- Touch -----------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selection != null) return selectionEvent(event)
        touchX = event.x
        touchY = event.y
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            val (column, row) = cellAt(event.x, event.y)
            touchColumn = column
            touchRow = row
            // The pane owns the gesture: a parent that scrolls would take it from a drag along the history.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        pinch.onTouchEvent(event)
        if (pinch.isInProgress) {
            axis = Axis.None
            return true
        }
        val consumed = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && axis == Axis.Horizontal) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * HORIZONTAL_RATIO) onSwipe?.invoke(dx < 0)
        }
        return consumed || super.onTouchEvent(event)
    }

    private fun longPress(e: MotionEvent) {
        val active = session ?: return
        val frame = currentFrame() ?: return
        val (column, row) = cellAt(e.x, e.y)
        // An agent prints the pull requests and sign-in pages it wants opened: offer them rather than their text.
        val url = active.hyperlinkAt(column, row)?.let(TerminalLinks::webUrl) ?: TerminalLinks.urlAt(frame, column, row)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (url != null) {
            onLink?.invoke(url)
            return
        }
        selection = TerminalSelection(frame, column, row).also { it.word() }
        // The finger that held the press carries on, and it moves the end of the word it selected.
        selectionDrag = true
        grabbedAnchor = false
        parent?.requestDisallowInterceptTouchEvent(true)
        startSelectionMode()
        invalidate()
    }

    private fun selectionEvent(event: MotionEvent): Boolean {
        val current = selection ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionDrag = true
                val toStart = hypot(event.x - x(current.first.column), event.y - (current.first.row + 1) * cellHeight)
                val toEnd = hypot(event.x - x(current.endColumn(current.last)), event.y - (current.last.row + 1) * cellHeight)
                if (min(toStart, toEnd) <= HANDLE_REACH_DP * density) {
                    // The start handle is the anchor unless the selection was dragged backwards.
                    grabbedAnchor = (toStart < toEnd) == (current.anchor <= current.extent)
                } else {
                    clearSelection()
                }
            }
            MotionEvent.ACTION_MOVE -> if (selectionDrag && event.pointerCount == 1) {
                val (column, row) = cellAt(event.x, event.y)
                current.move(column, row, moveAnchor = grabbedAnchor)
                selectionMode?.invalidateContentRect()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> selectionDrag = false
        }
        return true
    }

    private fun startSelectionMode() {
        selectionMode = startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(0, android.R.id.copy, 0, android.R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, android.R.id.selectAll, 1, android.R.string.selectAll).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
                    android.R.id.copy -> {
                        val text = selection?.text().orEmpty()
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
                        mode.finish()
                        true
                    }
                    android.R.id.selectAll -> {
                        selection?.all()
                        invalidate()
                        mode.invalidateContentRect()
                        true
                    }
                    else -> false
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    if (selectionMode === mode) {
                        selectionMode = null
                        selection = null
                        invalidate()
                    }
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    val s = selection ?: return super.onGetContentRect(mode, view, outRect)
                    outRect.set(
                        x(s.first.column).toInt(),
                        (s.first.row * cellHeight).toInt(),
                        min(width, x(s.endColumn(s.last)).toInt()),
                        min(height, ((s.last.row + 1) * cellHeight).toInt()),
                    )
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    private fun clearSelection() {
        selectionDrag = false
        val mode = selectionMode
        selectionMode = null
        selection = null
        mode?.finish()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clearSelection()
        scroller.abortAnimation()
        super.onDetachedFromWindow()
    }

    // ---- Keys and text ---------------------------------------------------------------------

    /** Types text the keyboard committed. */
    fun sendText(text: CharSequence) {
        session?.sendText(text)
    }

    /** What the keyboard is composing, shown over the cursor until it is committed or dropped. */
    fun setComposingText(text: String) {
        if (composing == text) return
        composing = text
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val active = session ?: return false
        if (event.isShiftPressed && (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            val rows = currentFrame()?.rows ?: return false
            active.scroll(if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -rows else rows)
            lastScrollAt = SystemClock.uptimeMillis()
            return true
        }
        // The text the key types without Ctrl or Alt: the terminal turns it into the chord's bytes.
        val chord = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
        val point = event.getUnicodeChar(event.metaState and chord.inv())
        // Only printable text: Enter, Tab and Escape are named by their keys, not by their control characters.
        val text = if (point >= ' '.code && point != DELETE && point and COMBINING_ACCENT == 0) String(Character.toChars(point)) else null
        val encoded = active.encodeKey(keyCode, event.repeatCount > 0, event.metaState, text)
        if (encoded != null) {
            active.send(encoded)
            handled.add(keyCode)
            return true
        }
        // A character no key of the table makes: typed as it is, unless a chord needs a key to name it.
        if (text != null && event.metaState and chord == 0) {
            active.sendText(text)
            handled.add(keyCode)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handled.remove(keyCode)

    private companion object {
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 40f
        const val PINCH_STEP_SP = 0.25f
        const val SWIPE_DP = 72f
        const val HORIZONTAL_RATIO = 1.5f
        const val HANDLE_REACH_DP = 28f
        const val FLING_RANGE = 1_000_000
        const val MAX_STEPS_PER_MOVE = 12
        const val WHEEL_LINES = 3
        const val FAINT_ALPHA = 150
        const val SCROLLBAR_MS = 900L
        const val COMBINING_ACCENT = Int.MIN_VALUE
        const val DELETE = 0x7f
    }
}
