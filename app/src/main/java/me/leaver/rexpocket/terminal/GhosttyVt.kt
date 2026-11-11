package me.leaver.rexpocket.terminal

/**
 * The official libghostty-vt, through native/ghostty/jni.c. A handle is one terminal; it is not
 * thread-safe, so [TerminalSession] serializes every call made with it.
 */
internal object GhosttyVt {
    init {
        System.loadLibrary("ghostty_jni")
    }

    /** 0 when the terminal could not be made. */
    @JvmStatic external fun nativeCreate(columns: Int, rows: Int): Long

    /** A terminal rebuilt from a `GHOSTSNP` snapshot: scrollback, colours, cursor and all. 0 on failure. */
    @JvmStatic external fun nativeRestore(snapshot: ByteArray): Long
    @JvmStatic external fun nativeFree(handle: Long)

    /** libghostty's result code: 0 on success. */
    @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeResize(handle: Long, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Int

    /** [which] is [COLOR_FOREGROUND] or [COLOR_BACKGROUND]; the alpha of [argb] is ignored. */
    @JvmStatic external fun nativeSetColor(handle: Long, which: Int, argb: Int): Int

    /** Rows towards the newer output when positive. */
    @JvmStatic external fun nativeScroll(handle: Long, lines: Int)
    @JvmStatic external fun nativeScrollToBottom(handle: Long)

    /** The viewport as the words [TerminalFrame.parse] reads; null if the terminal could not be read. */
    @JvmStatic external fun nativeFrame(handle: Long): IntArray?

    /** What the program reads for a wheel step over a cell, or null/empty if it asked for no mouse. */
    @JvmStatic external fun nativeWheel(handle: Long, down: Boolean, column: Int, row: Int, cellWidthPx: Int, cellHeightPx: Int): ByteArray?
    @JvmStatic external fun nativeClick(handle: Long, column: Int, row: Int, cellWidthPx: Int, cellHeightPx: Int): ByteArray?

    /** What the program reads for an Android key, in its current modes; null if the key is not one it encodes. */
    @JvmStatic external fun nativeKey(handle: Long, keyCode: Int, repeat: Boolean, metaState: Int, text: String?): ByteArray?

    /**
     * A stored Kitty image as `[width, height, ARGB...]`, or null when there is no such image or its
     * pixels have not all arrived.
     */
    @JvmStatic external fun nativeImage(handle: Long, imageId: Int): IntArray?

    /**
     * libghostty-vt has no PNG decoder of its own, so jni.c lends it this one, Android's, for the
     * Kitty images programs send as PNG. Called from native code while a terminal is being written
     * to: `[width, height, ARGB...]`, or null when the bytes are not an image it can read.
     */
    @JvmStatic
    fun decodePng(png: ByteArray): IntArray? {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            // The same ceiling libghostty puts on a decoded image, checked before allocating it.
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_IMAGE_PIXELS) return null
            val out = IntArray(2 + width * height)
            out[0] = width
            out[1] = height
            bitmap.getPixels(out, 2, width, 0, 0, width, height)
            out
        } finally {
            bitmap.recycle()
        }
    }

    /** 400 MB of RGBA, libghostty's own limit for one decoded image. */
    private const val MAX_IMAGE_PIXELS = 100L * 1024 * 1024

    /** The OSC 8 link on a viewport cell, UTF-8, or null. */
    @JvmStatic external fun nativeHyperlinkAt(handle: Long, column: Int, row: Int): ByteArray?

    const val COLOR_FOREGROUND = 0
    const val COLOR_BACKGROUND = 1
}
