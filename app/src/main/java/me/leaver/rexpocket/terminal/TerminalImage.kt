package me.leaver.rexpocket.terminal

/**
 * One Kitty graphics placement on screen, as libghostty-vt's render state works it out: which part
 * of which image, and the rectangle it covers. [column] and [row] are the viewport cell of its
 * top-left corner and can be negative for an image partly scrolled out; every other measure is in
 * the terminal's pixels, [TerminalFrame.cellWidthPx] by [TerminalFrame.cellHeightPx] a cell.
 */
internal data class TerminalImage(
    val id: Int,
    /** Changes whenever the image's pixels may have: the key a cached bitmap is kept under. */
    val generation: Long,
    val layer: Int,
    val z: Int,
    val column: Int,
    val row: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    companion object {
        const val LAYER_BELOW_BACKGROUND = 0
        const val LAYER_BELOW_TEXT = 1
        const val LAYER_ABOVE_TEXT = 2
    }
}

/** A stored image's pixels, ready for an Android Bitmap. */
internal class ImagePixels(val width: Int, val height: Int, val argb: IntArray)
