package app.rexpocket.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.rexpocket.core.VtScreen
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var snapshot: VtScreen.Snapshot? = null
        set(value) {
            field = value
            invalidate()
        }

    var fontScale: Float = 1f
        set(value) {
            val next = value.coerceIn(0.7f, 1.8f)
            if (field == next) return
            field = next
            requestLayout()
            reportSize()
            invalidate()
        }

    var onGrid: ((cols: Int, rows: Int, cellW: Int, cellH: Int) -> Unit)? = null
    var onSwipeWindow: ((forward: Boolean) -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onFontScale: ((Float) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = 0xFFC5C8C6.toInt()
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint()

    private var scrollLines = 0
    private var followTail = true
    private var selAnchor: Pair<Int, Int>? = null
    private var selFocus: Pair<Int, Int>? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (selAnchor != null && e1 != null && abs(e2.x - e1.x) + abs(e2.y - e1.y) > 12) {
                cellAt(e2.x, e2.y)?.let { selFocus = it }
                invalidate()
                return true
            }
            val line = charH()
            if (line <= 0f) return false
            if (abs(distanceX) > abs(distanceY) * 1.4f) return false
            followTail = false
            val max = snapshot?.scrollback?.size ?: 0
            scrollLines = (scrollLines + (distanceY / line).toInt()).coerceIn(0, max)
            if (scrollLines == 0) followTail = true
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > 72 && abs(dx) > abs(dy) * 1.3f && abs(velocityX) > 600) {
                onSwipeWindow?.invoke(dx < 0)
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onPaste?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val cell = cellAt(e.x, e.y) ?: return
            val word = wordRange(cell.first, cell.second)
            selAnchor = word.first
            selFocus = word.second
            followTail = false
            invalidate()
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onFontScale?.invoke(fontScale * detector.scaleFactor)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && scrollLines == 0) followTail = true
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportSize()
    }

    fun selectedText(): String {
        val snap = snapshot ?: return ""
        val a = selAnchor ?: return ""
        val b = selFocus ?: a
        val lines = linesOf(snap)
        val start = minPos(a, b)
        val end = maxPos(a, b)
        return buildString {
            for (row in start.first..end.first) {
                val line = lines.getOrNull(row) ?: continue
                val from = if (row == start.first) start.second else 0
                val to = if (row == end.first) end.second else line.lastIndex
                for (col in from..to.coerceAtMost(line.lastIndex)) {
                    val cell = line[col]
                    if (!cell.wideTail) append(if (cell.ch.isEmpty()) " " else cell.ch)
                }
                if (row != end.first) append('\n')
            }
        }.trimEnd()
    }

    fun clearSelection() {
        selAnchor = null
        selFocus = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val snap = snapshot
        val bg = snap?.defaultBg ?: 0xFF1D1F21.toInt()
        canvas.drawColor(bg)
        if (snap == null) return
        val cw = charW()
        val ch = charH()
        if (cw <= 0f || ch <= 0f) return
        val lines = linesOf(snap)
        val start = ((lines.size - snap.rows - if (followTail) 0 else scrollLines).coerceAtLeast(0))
        val visible = snap.rows.coerceAtMost(lines.size - start)
        val sel = selectionSpan()
        for (i in 0 until visible) {
            val row = lines[start + i]
            var col = 0
            while (col < row.size) {
                val cell = row[col]
                if (cell.wideTail) {
                    col++
                    continue
                }
                val span = if (cell.ch.isNotEmpty() && isWide(cell.ch)) 2 else 1
                val selected = sel != null && inSel(start + i, col, sel)
                val fg = if (cell.inverse) resolve(cell.bg, bg) else resolve(cell.fg, snap.defaultFg)
                val cellBg = if (cell.inverse) resolve(cell.fg, snap.defaultFg) else resolve(cell.bg, bg)
                bgPaint.color = if (selected) 0xFF244B32.toInt() else cellBg
                val x = paddingLeft + col * cw
                val y = paddingTop + i * ch
                canvas.drawRect(x, y, x + cw * span, y + ch, bgPaint)
                textPaint.color = fg
                textPaint.isFakeBoldText = cell.bold
                textPaint.isUnderlineText = cell.underline
                val glyph = if (cell.ch.isEmpty()) " " else cell.ch
                canvas.drawText(glyph, x, y + textPaint.textSize + (ch - textPaint.textSize) / 4f, textPaint)
                col += span
            }
        }
        if (snap.cursorOn && followTail && scrollLines == 0) {
            val cx = snap.cursorX.coerceIn(0, snap.cols - 1)
            val cy = snap.cursorY.coerceIn(0, snap.rows - 1)
            val screenStart = (lines.size - snap.rows).coerceAtLeast(0)
            val visRow = screenStart + cy - start
            if (visRow in 0 until visible) {
                cursorPaint.color = snap.cursorColor
                cursorPaint.alpha = 180
                val x = paddingLeft + cx * cw
                val y = paddingTop + visRow * ch
                canvas.drawRect(x, y, x + cw, y + ch, cursorPaint)
            }
        }
    }

    private fun linesOf(snap: VtScreen.Snapshot): List<List<VtScreen.CellSnap>> {
        val history = snap.scrollback.map { line ->
            line.map { ch ->
                VtScreen.CellSnap(ch.toString(), -1, -1, false, false, false, false)
            }
        }
        return history + snap.screen
    }

    private fun reportSize() {
        val cw = charW()
        val ch = charH()
        if (cw <= 0f || ch <= 0f || width == 0 || height == 0) return
        val cols = floor((width - paddingLeft - paddingRight) / cw).toInt().coerceIn(20, 400)
        val rows = floor((height - paddingTop - paddingBottom) / ch).toInt().coerceIn(4, 200)
        onGrid?.invoke(cols, rows, ceil(cw).toInt().coerceAtLeast(1), ceil(ch).toInt().coerceAtLeast(1))
    }

    private fun charW(): Float {
        textPaint.textSize = 15f * resources.displayMetrics.density * fontScale
        return textPaint.measureText("M")
    }

    private fun charH(): Float {
        textPaint.textSize = 15f * resources.displayMetrics.density * fontScale
        return textPaint.fontSpacing
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val snap = snapshot ?: return null
        val cw = charW()
        val ch = charH()
        if (cw <= 0f || ch <= 0f) return null
        val lines = linesOf(snap)
        val start = ((lines.size - snap.rows - if (followTail) 0 else scrollLines).coerceAtLeast(0))
        val col = ((x - paddingLeft) / cw).toInt()
        val row = start + ((y - paddingTop) / ch).toInt()
        if (row !in lines.indices || col !in 0 until snap.cols) return null
        return row to col
    }

    private fun wordRange(row: Int, col: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val snap = snapshot ?: return (row to col) to (row to col)
        val line = linesOf(snap).getOrNull(row) ?: return (row to col) to (row to col)
        fun isWord(c: String) = c.isNotEmpty() && !c[0].isWhitespace()
        var a = col
        var b = col
        while (a > 0 && isWord(line[a - 1].ch)) a--
        while (b + 1 < line.size && isWord(line[b + 1].ch)) b++
        return (row to a) to (row to b)
    }

    private fun selectionSpan(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val a = selAnchor ?: return null
        val b = selFocus ?: a
        return minPos(a, b) to maxPos(a, b)
    }

    private fun inSel(row: Int, col: Int, sel: Pair<Pair<Int, Int>, Pair<Int, Int>>): Boolean {
        val (start, end) = sel
        if (row < start.first || row > end.first) return false
        if (row == start.first && col < start.second) return false
        if (row == end.first && col > end.second) return false
        return true
    }

    private fun minPos(a: Pair<Int, Int>, b: Pair<Int, Int>) = if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a else b
    private fun maxPos(a: Pair<Int, Int>, b: Pair<Int, Int>) = if (a === minPos(a, b)) b else a

    private fun resolve(color: Int, fallback: Int) = if (color < 0) fallback else color

    private fun isWide(ch: String): Boolean {
        if (ch.isEmpty()) return false
        return app.rexpocket.core.VtScreen.charWidth(ch.codePointAt(0)) == 2
    }
}
