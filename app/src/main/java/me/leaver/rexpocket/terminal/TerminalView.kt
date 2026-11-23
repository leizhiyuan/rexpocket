package me.leaver.rexpocket.terminal

import me.leaver.rexpocket.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
import me.leaver.rexpocket.core.Gesture
import me.leaver.rexpocket.core.TapGestures
import me.leaver.rexpocket.core.TapTiming
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
            // Image ids belong to one emulator; a new session's image 1 is another picture.
            dropBitmaps()
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

    /** Paste in the selection's menu: what the clipboard holds goes to the program, as the bar's Paste. */
    var onPaste: (() -> Unit)? = null

    /** Share in the selection's menu: the selected text, offered to another app. */
    var onShare: ((String) -> Unit)? = null

    /** A tap that was not the start of anything else. */
    var onTap: (() -> Unit)? = null

    /**
     * A second tap soon after a first, in the same place; true when the first one already went out
     * as a tap, which it does unless [gestureTiming] waits for a second. Null, the default, leaves
     * every tap a tap, at once, as before there was one.
     */
    var onDoubleTap: ((firstTapActed: Boolean) -> Unit)? = null
        set(value) {
            field = value
            applyTiming()
        }

    /**
     * A press held still, when it is not to select: a link under the finger is still offered first.
     * Null, the default, selects the word under it, as a long press always did.
     */
    var onLongPress: (() -> Unit)? = null

    /**
     * How taps, double taps and long presses are told apart: the person's times, or the phone's.
     * Its double taps count only while [onDoubleTap] listens for them.
     */
    var gestureTiming: TapTiming = systemTiming()
        set(value) {
            if (field == value) return
            field = value
            applyTiming()
        }

    /** A horizontal swipe; true when it went towards the next pane. */
    var onSwipe: ((forward: Boolean) -> Unit)? = null

    /**
     * Where the cursor's row is, as the pane draws it: its top in this view's pixels, or null once no
     * cursor has shown for a moment. Called when it changes. The screen reads it to keep that row in
     * view while the keyboard covers the bottom of the pane.
     *
     * A program that hides the cursor only while it redraws would otherwise flick the answer to
     * null and back, so a cursor that goes away is reported only once it has stayed away for
     * [CURSOR_GONE_MS]. While the pane is scrolled back into the history nothing is reported: the
     * cursor is out of view because the person is reading, and the pane stays where it is.
     */
    var onCursorRow: ((topPx: Int?) -> Unit)? = null

    /**
     * The bottom of what the program has drawn on the live screen, in this view's pixels, when that
     * stops short of the pane's last row: the cursor's row, or a lower one that has something on it.
     * Null when the drawing reaches the last row, or on the alternate screen, where a full-screen
     * program owns every row. Reported with [onCursorRow], and not while the pane is scrolled back.
     * The screen reads it so the keyboard moves a short screen - a new shell, a few prompts - only
     * as far as its last row needs, and not up to the cursor's row.
     */
    var onDrawnBottom: ((bottomPx: Int?) -> Unit)? = null

    private var drawnBottom: Int? = null
    private var cursorTop: Int? = null
    private var cursorReported = false
    private var cursorGonePending = false
    private val cursorGone = Runnable {
        cursorGonePending = false
        reportCursor(null)
    }

    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val bold = Paint(regular)
    private val italic = Paint(regular)
    private val boldItalic = Paint(regular)
    private val fill = Paint()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageSource = Rect()
    private val imageTarget = RectF()

    /** Decoded Kitty images, by id; a bitmap is made again only when its image's generation moves. */
    private val bitmaps = HashMap<Int, Pair<Long, Bitmap>>()
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

    /**
     * Taps, double taps and long presses, told apart by [TapGestures] from the touches and the
     * time; the detector below keeps drags and flings. A double tap acts when its second finger
     * lifts, so a second press held becomes a long press and one that moves is a drag.
     */
    private val taps = TapGestures()

    /** True after a long press until the finger lifts: the rest of that touch is not a drag. */
    private var longPressed = false

    /**
     * The main thread's handler, not the view's own post: a view not yet in a window holds what is
     * posted to it until it is, and a gesture's time must pass whether or not it is.
     */
    private val tapClock = android.os.Handler(android.os.Looper.getMainLooper())

    private val tapTimer = Runnable {
        dispatch(taps.timer(SystemClock.uptimeMillis()))
        scheduleTaps()
    }

    private fun systemTiming(): TapTiming {
        val config = android.view.ViewConfiguration.get(context)
        return TapTiming(
            doubleTapMs = android.view.ViewConfiguration.getDoubleTapTimeout().toLong(),
            longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong(),
            touchSlop = config.scaledTouchSlop.toFloat(),
            doubleTapSlop = config.scaledDoubleTapSlop.toFloat(),
        )
    }

    private fun applyTiming() {
        taps.timing = gestureTiming.copy(doubleTaps = gestureTiming.doubleTaps && onDoubleTap != null)
    }

    private fun scheduleTaps() {
        tapClock.removeCallbacks(tapTimer)
        val at = taps.deadline ?: return
        tapClock.postDelayed(tapTimer, (at - SystemClock.uptimeMillis() + 1).coerceAtLeast(0))
    }

    private fun dispatch(gestures: List<Gesture>) {
        for (gesture in gestures) {
            when (gesture) {
                is Gesture.Tap -> {
                    val frame = currentFrame()
                    val active = session
                    // A program that reads the mouse gets its click, whatever the tap does for the app.
                    if (frame != null && active != null && frame.mouseTracking && frame.atBottom) {
                        val (column, row) = cellAt(gesture.x, gesture.y)
                        active.sendRaw(active.encodeClick(column, row, cellWidth.roundToInt(), cellHeight.roundToInt()))
                    }
                    onTap?.invoke()
                }
                is Gesture.DoubleTap -> onDoubleTap?.invoke(gesture.firstTapActed)
                is Gesture.LongPress -> {
                    longPressed = true
                    longPress(gesture.x, gesture.y)
                }
            }
        }
    }

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

        },
    ).apply {
        // Taps, double taps and long presses are TapGestures' own, with the person's times.
        setOnDoubleTapListener(null)
        setIsLongpressEnabled(false)
    }

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

    /** The lines TalkBack reads; see [TerminalAccessibility]. */
    internal val access = TerminalAccessibility(this)

    init {
        // Typing goes through TerminalImeView, which sits under this view: its own input connection
        // would answer the keyboard with no text type at all.
        isFocusable = false
        isFocusableInTouchMode = false
        isLongClickable = true
        applyTiming()
        measureFont()
        // TalkBack reads the pane line by line, and the output that comes while it listens there.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.terminal_a11y_label)
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, access)
    }

    /** What the pane shows now, for [access]. */
    internal fun screenFrame(): TerminalFrame? = selection?.frame ?: currentFrame()

    /** How many of [frame]'s rows the pane has room to show, for [access]. */
    internal fun visibleRows(frame: TerminalFrame): Int =
        if (height <= 0) 0 else min(frame.rows, ceil(height / cellHeight).toInt())

    /** A row's height in pixels, for [access]. */
    internal val rowHeight: Float get() = cellHeight

    /** TalkBack's double tap on the pane, or on a line of it: what a tap does. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * True while a keyboard other than the screen's is attached. The pane then takes the focus - it
     * is not a text editor, so nothing raises the screen keyboard for it - and the keys a desk
     * keyboard sends come straight here, without a tap on the pane first.
     */
    var hardwareKeys: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
            if (!value) clearFocus()
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
        val images = if (frame.images.isEmpty()) emptyList() else frame.images.sortedWith(compareBy({ it.layer }, { it.z }))
        keepBitmaps(images)
        drawImages(canvas, frame, images, TerminalImage.LAYER_BELOW_BACKGROUND)
        for (row in 0 until rows) drawBackgrounds(canvas, frame, row, columns)
        drawImages(canvas, frame, images, TerminalImage.LAYER_BELOW_TEXT)
        for (row in 0 until rows) drawText(canvas, frame, row, columns)
        drawImages(canvas, frame, images, TerminalImage.LAYER_ABOVE_TEXT)
        if (selection == null) {
            drawCursor(canvas, frame)
            noteCursor(frame)
        }
        drawHandles(canvas)
        drawScrollbar(canvas, frame)
        access.screenChanged(frame)
    }

    /**
     * The Kitty images of one layer, in z order. Their rectangles are in the terminal's pixels; the
     * pane's own cells can differ from those by rounding, or for the moment a desktop-sized snapshot
     * is on screen before the resize lands, so they are scaled cell for cell. Clipped to the view: an
     * image can start above it or run past its edge.
     */
    private fun drawImages(canvas: Canvas, frame: TerminalFrame, images: List<TerminalImage>, layer: Int) {
        if (images.isEmpty() || frame.cellWidthPx <= 0 || frame.cellHeightPx <= 0) return
        val scaleX = cellWidth / frame.cellWidthPx
        val scaleY = cellHeight / frame.cellHeightPx
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        for (image in images) {
            if (image.layer != layer || image.width <= 0 || image.height <= 0) continue
            val bitmap = bitmapFor(image) ?: continue
            val sw = if (image.sourceWidth > 0) image.sourceWidth else bitmap.width
            val sh = if (image.sourceHeight > 0) image.sourceHeight else bitmap.height
            imageSource.set(image.sourceX, image.sourceY, image.sourceX + sw, image.sourceY + sh)
            val left = (image.column * frame.cellWidthPx + image.offsetX) * scaleX
            val top = (image.row * frame.cellHeightPx + image.offsetY) * scaleY
            imageTarget.set(left, top, left + image.width * scaleX, top + image.height * scaleY)
            canvas.drawBitmap(bitmap, imageSource, imageTarget, imagePaint)
        }
        canvas.restore()
    }

    private fun bitmapFor(image: TerminalImage): Bitmap? {
        bitmaps[image.id]?.let { (generation, bitmap) -> if (generation == image.generation) return bitmap }
        // Not here yet when the program is still sending it; the next frame asks again.
        val pixels = session?.imagePixels(image.id) ?: return null
        val bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        bitmaps.put(image.id, image.generation to bitmap)?.second?.recycle()
        return bitmap
    }

    /** Lets go of the bitmaps of images no longer on screen; a scrolled-back image is decoded again. */
    private fun keepBitmaps(images: List<TerminalImage>) {
        if (bitmaps.isEmpty()) return
        val shown = images.mapTo(HashSet()) { it.id }
        val gone = bitmaps.keys.filter { it !in shown }
        for (id in gone) bitmaps.remove(id)?.second?.recycle()
    }

    private fun dropBitmaps() {
        for ((_, bitmap) in bitmaps.values) bitmap.recycle()
        bitmaps.clear()
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

    private fun noteCursor(frame: TerminalFrame) {
        if (!frame.atBottom) return
        val shown = frame.cursorX >= 0 && frame.cursorY >= 0 && frame.cursorY < frame.rows
        if (shown) {
            if (cursorGonePending) {
                tapClock.removeCallbacks(cursorGone)
                cursorGonePending = false
            }
            reportDrawnBottom(frame)
            reportCursor((frame.cursorY * cellHeight).roundToInt())
        } else if (cursorTop != null && !cursorGonePending) {
            cursorGonePending = true
            tapClock.postDelayed(cursorGone, CURSOR_GONE_MS)
        }
    }

    private fun reportDrawnBottom(frame: TerminalFrame) {
        val visible = min(frame.rows, ceil(height / cellHeight).toInt())
        val last = max(frame.cursorY, frame.lastDrawnRow(visible))
        val bottom = if (frame.alternateScreen || last >= visible - 1) null else ((last + 1) * cellHeight).roundToInt()
        if (bottom == drawnBottom) return
        drawnBottom = bottom
        onDrawnBottom?.invoke(bottom)
    }

    private fun reportCursor(top: Int?) {
        if (cursorReported && top == cursorTop) return
        cursorTop = top
        cursorReported = true
        onCursorRow?.invoke(top)
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
            longPressed = false
            downX = event.x
            downY = event.y
            val (column, row) = cellAt(event.x, event.y)
            touchColumn = column
            touchRow = row
            // The pane owns the gesture: a parent that scrolls would take it from a drag along the history.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        pinch.onTouchEvent(event)
        // Timed by the clock the timer runs on, not the event's own stamp, which a test or an
        // injected event may take from another clock.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dispatch(taps.down(SystemClock.uptimeMillis(), event.x, event.y))
            MotionEvent.ACTION_MOVE -> dispatch(taps.move(SystemClock.uptimeMillis(), event.x, event.y))
            MotionEvent.ACTION_UP -> dispatch(taps.up(SystemClock.uptimeMillis(), event.x, event.y))
            else -> taps.cancel()
        }
        scheduleTaps()
        if (pinch.isInProgress) {
            axis = Axis.None
            taps.cancel()
            return true
        }
        // A long press that offered a link or acted is the whole of its touch: no drag after it.
        if (longPressed) return true
        val consumed = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && axis == Axis.Horizontal) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * HORIZONTAL_RATIO) onSwipe?.invoke(dx < 0)
        }
        return consumed || super.onTouchEvent(event)
    }

    private fun longPress(x: Float, y: Float) {
        val active = session
        val frame = currentFrame()
        val (column, row) = cellAt(x, y)
        // An agent prints the pull requests and sign-in pages it wants opened: offer them rather
        // than their text, whatever a long press is set to do.
        val url = if (active != null && frame != null) {
            active.hyperlinkAt(column, row)?.let(TerminalLinks::webUrl) ?: TerminalLinks.urlAt(frame, column, row)
        } else {
            null
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (url != null) {
            onLink?.invoke(url)
            return
        }
        onLongPress?.let {
            it()
            return
        }
        if (active == null || frame == null) return
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
                    if (onShare != null) menu.add(0, android.R.id.shareText, 1, R.string.selection_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    if (onPaste != null) menu.add(0, android.R.id.paste, 2, android.R.string.paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    menu.add(0, android.R.id.selectAll, 3, android.R.string.selectAll).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
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
                    android.R.id.shareText -> {
                        val text = selection?.text().orEmpty()
                        mode.finish()
                        if (text.isNotEmpty()) onShare?.invoke(text)
                        true
                    }
                    android.R.id.paste -> {
                        mode.finish()
                        onPaste?.invoke()
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
        tapClock.removeCallbacks(tapTimer)
        tapClock.removeCallbacks(cursorGone)
        cursorGonePending = false
        taps.cancel()
        clearSelection()
        dropBitmaps()
        scroller.abortAnimation()
        access.release()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A desk keyboard types here, and the pane is the only view that can take the keys for it.
        if (hardwareKeys) requestFocus()
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

    /**
     * Offered every key from a keyboard before the terminal sees it; true when the screen took it
     * as its own shortcut, such as the command palette's, so the program never gets it.
     */
    var onShortcut: ((KeyEvent) -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onShortcut?.invoke(event) == true) {
            handled.add(keyCode)
            return true
        }
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
        /** How long the cursor must stay hidden before the pane counts it as gone. */
        const val CURSOR_GONE_MS = 250L
        const val COMBINING_ACCENT = Int.MIN_VALUE
        const val DELETE = 0x7f
    }
}
