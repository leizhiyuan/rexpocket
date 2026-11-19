package me.leaver.rexpocket.data

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import java.io.File

internal fun downloadedTypeface(files: File, font: TerminalFont): Typeface {
    if (Build.VERSION.SDK_INT < 29) return Typeface.createFromFile(File(files, font.assets.first().name))
    fun face(asset: FontAsset): Font = Font.Builder(File(files, asset.name))
        .setWeight(asset.weight)
        .setSlant(if (asset.italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
        .build()
    val family = FontFamily.Builder(face(font.assets.first()))
    font.assets.drop(1).forEach { family.addFont(face(it)) }
    return Typeface.CustomFallbackBuilder(family.build()).setSystemFallback("sans-serif").build()
}

internal data class TerminalFontState(
    val selected: String = SYSTEM_FONT,
    val available: Set<String> = emptySet(),
    val initializing: Boolean = true,
    val downloading: String? = null,
    val progress: Float = 0f,
    val failed: String? = null,
)
