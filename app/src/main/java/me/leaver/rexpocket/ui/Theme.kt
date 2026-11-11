package me.leaver.rexpocket.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import me.leaver.rexpocket.terminal.TerminalTheme

/**
 * The app's one design system, in the two appearances the phone has: light grouped paper and the
 * near-black the reference terminal uses at night. One blue for anything you can act on, a dark
 * tile with a green prompt as the only strong mark, and the terminal as the paper you sit on.
 *
 * Contrast, measured against [Colors.surface] and [Colors.bg], light then dark: ink 17.0/15.3 and
 * 15.9/18.9, muted 6.1/5.5 and 6.6/6.9, accent 5.4/4.8 and 5.6/6.4, ok 5.4/4.9 and 8.4/9.9, amber
 * 5.1/4.5 and 13.4/15.9, danger 5.9/5.3 and 5.1/5.6. Text on [Colors.accent] is [Colors.onAccent];
 * the terminal glyph on [Colors.badge] is [Colors.badgeInk] at 8.4.
 */
private data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val terminal: Color,
    val key: Color,
    val keyOn: Color,
    val line: Color,
    val edge: Color,
    val shadow: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val amber: Color,
    val danger: Color,
    val badge: Color,
    val badgeInk: Color,
    val tile: Color,
    val tileInk: Color,
    val scrim: Color,
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF),
    terminal = Color(0xFFFFFFFF),
    key = Color(0xFFE9E9EF),
    keyOn = Color(0xFFDCE7FB),
    line = Color(0xFFD9D9DE),
    edge = Color(0x0F000000),
    shadow = Color(0x40000000),
    ink = Color(0xFF1C1C1E),
    muted = Color(0xFF63636A),
    accent = Color(0xFF0A62E8),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF137A3D),
    amber = Color(0xFF9A6300),
    danger = Color(0xFFC7162B),
    badge = Color(0xFF1C1C1E),
    badgeInk = Color(0xFF30D158),
    tile = Color(0xFFF2F0EB),
    tileInk = Color(0xFF1C1C1E),
    scrim = Color(0x40000000),
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF0A0A0B),
    surface = Color(0xFF161618),
    raised = Color(0xFF242427),
    terminal = Color(0xFF0A0A0B),
    key = Color(0xFF232326),
    keyOn = Color(0xFF12345C),
    line = Color(0xFF2C2C2E),
    edge = Color(0xFF323236),
    shadow = Color(0x00000000),
    ink = Color(0xFFF2F2F7),
    muted = Color(0xFF98989F),
    accent = Color(0xFF0A84FF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF30D158),
    amber = Color(0xFFFFD60A),
    danger = Color(0xFFFF453A),
    badge = Color(0xFF2C2C2E),
    badgeInk = Color(0xFF30D158),
    tile = Color(0xFFE9E7E2),
    tileInk = Color(0xFF1C1C1E),
    scrim = Color(0x99000000),
)

/**
 * The palette is the current appearance, read through state so every screen that reads a colour is
 * recomposed when the phone goes from light to dark. [RexTheme] sets it once per appearance.
 */
object Colors {
    private var palette by mutableStateOf(Light)

    /** The page. */
    val bg: Color get() = palette.bg

    /** A card, a sheet, a row: the surface that holds content. */
    val surface: Color get() = palette.surface

    /**
     * A control that floats over the content: the home's pills, the terminal's dock and pane bar.
     * White in the day; at night one step lighter than a card, because a shadow does not show there.
     */
    val raised: Color get() = palette.raised

    /** The pane's own paper: white in the day, the page at night. */
    val terminal: Color get() = palette.terminal

    /** A key in the dock, the plus, an inactive plate. */
    val key: Color get() = palette.key

    /** The armed plate behind a latched key such as Ctrl. */
    val keyOn: Color get() = palette.keyOn

    /** Hairlines and inactive edges. Decoration only, never a control boundary. */
    val line: Color get() = palette.line

    /** The rim of a floating control: barely there in the day, the only edge at night. */
    val edge: Color get() = palette.edge

    /** The shadow under a floating control; none at night. */
    val shadow: Color get() = palette.shadow

    /** Titles and body text. */
    val ink: Color get() = palette.ink

    /** Labels, captions, secondary detail, and the key labels. */
    val muted: Color get() = palette.muted

    /** The one action colour: links, buttons, the live tab. */
    val accent: Color get() = palette.accent

    /** Text and icons drawn on [accent]. */
    val onAccent: Color get() = palette.onAccent

    /** Running, online, joined, a direct path. */
    val ok: Color get() = palette.ok

    /** Waiting on a person, a warning. */
    val amber: Color get() = palette.amber

    /** Something failed, or an action that removes. */
    val danger: Color get() = palette.danger

    /** The tile behind a terminal glyph. */
    val badge: Color get() = palette.badge

    /** The glyph on [badge]. */
    val badgeInk: Color get() = palette.badgeInk

    /**
     * The light plate behind a program's mark - an agent, an editor - as the desktop's sidebar draws
     * it; a shell keeps the dark [badge]. Light in both appearances, so a mark reads the same.
     */
    val tile: Color get() = palette.tile

    /** A program's mark on [tile]. */
    val tileInk: Color get() = palette.tileInk

    /** Behind a sheet. */
    val scrim: Color get() = palette.scrim

    /** Nothing drawn; the far end of an edge fade. A token rather than a literal at the call site. */
    val clear: Color = Color(0x00000000)

    /** True while the night palette is in use. */
    val isDark: Boolean get() = palette.dark

    internal fun use(dark: Boolean) {
        val next = if (dark) Dark else Light
        if (palette != next) palette = next
    }
}

/**
 * Picks the palette from the phone's appearance and keeps the system bars in step with it. The
 * Material theme under it carries the same colours, so the platform's own pieces - the ripple on a
 * press, a dialog, a menu - look like the rest of the app.
 */
@Composable
fun RexTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.muted, outline = p.line,
            outlineVariant = p.line, surfaceContainer = p.raised, surfaceContainerHigh = p.raised,
            surfaceContainerHighest = p.key, error = p.danger, scrim = p.scrim,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.muted, outline = p.line,
            outlineVariant = p.line, surfaceContainer = p.raised, surfaceContainerHigh = p.raised,
            surfaceContainerHighest = p.key, error = p.danger, scrim = p.scrim,
        )
    }
    val view = LocalView.current
    SideEffect {
        Colors.use(dark)
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The spacing scale, in the steps the screens actually use. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

object Radius {
    val chip = 12.dp
    val card = 16.dp
    val dock = 24.dp
    val sheet = 24.dp
}

/**
 * Five sizes, five jobs: an empty screen's title, a sheet or dialog title, a row's title, a group
 * header, and a label. Nothing else is allowed, which is what keeps the app from collecting a dozen
 * near-identical font sizes. They are the platform's own steps (headline, title, body, title-small,
 * body-small) rather than a second scale beside it.
 */
object TextStyles {
    val display = 28.sp
    val title = 20.sp
    val body = 16.sp
    val subhead = 14.sp
    val label = 13.sp
}

/** Line height for multi-line labels at [TextStyles.label], such as a dialog's hint. */
val labelLeading: TextUnit = 18.sp

/**
 * The terminal's own paper and ink, for the appearance in use: white paper in the day, the page at
 * night. Every palette entry stays the program's; only the defaults a program never sets bend.
 */
internal fun terminalTheme(): TerminalTheme = TerminalTheme(
    foreground = Colors.ink.toArgb(),
    background = Colors.terminal.toArgb(),
    cursor = Colors.accent.toArgb(),
    selection = Colors.line.toArgb(),
)
