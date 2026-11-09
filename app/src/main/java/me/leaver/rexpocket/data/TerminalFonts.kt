package me.leaver.rexpocket.data

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import java.io.File

internal fun downloadedTypeface(files: File): Typeface {
    if (Build.VERSION.SDK_INT < 29) return Typeface.createFromFile(File(files, "JetBrainsMono-Regular.ttf"))
    val family = FontFamily.Builder(Font.Builder(File(files, "JetBrainsMono-Regular.ttf")).build())
    for ((name, weight, slant) in listOf(
        Triple("JetBrainsMono-Bold.ttf", 700, FontStyle.FONT_SLANT_UPRIGHT),
        Triple("JetBrainsMono-Italic.ttf", 400, FontStyle.FONT_SLANT_ITALIC),
        Triple("JetBrainsMono-BoldItalic.ttf", 700, FontStyle.FONT_SLANT_ITALIC),
    )) family.addFont(Font.Builder(File(files, name)).setWeight(weight).setSlant(slant).build())
    return Typeface.CustomFallbackBuilder(family.build()).setSystemFallback("sans-serif").build()
}

internal data class TerminalFontState(
    val selected: Boolean = false,
    val available: Boolean = false,
    val initializing: Boolean = true,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val failed: Boolean = false,
)
