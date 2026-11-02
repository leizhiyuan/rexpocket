package me.leaver.rexpocket.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sagernet.libghostty.GhosttyTheme

/**
 * The app's one design system: light iOS paper, one blue for anything you can act on, and a dark
 * terminal tile as the only strong dark mark. Every colour, size and radius on screen comes from
 * here, so a screen cannot drift.
 *
 * Contrast, measured against [Colors.surface] and [Colors.bg]: ink 17.0 / 15.3, muted 6.1 / 5.5,
 * accent 5.4 / 4.8, ok 5.4 / 4.9, amber 5.1 / 4.5, danger 5.9 / 5.3. Text on the accent is
 * [Colors.onAccent]; the terminal glyph on [Colors.badge] is [Colors.badgeInk] at 8.4.
 */
object Colors {
    /** The page. Everything sits on it except a card. */
    val bg = Color(0xFFF2F2F7)

    /** A card, a sheet, the terminal, the dock: the surface that holds content. */
    val surface = Color(0xFFFFFFFF)

    /** A key in the dock, the plus, and an inactive tile. */
    val key = Color(0xFFE9E9EF)

    /** The armed plate behind a latched key such as Ctrl. */
    val keyOn = Color(0xFFDCE7FB)

    /** Hairlines and inactive edges. Decoration only, never a control boundary. */
    val line = Color(0xFFD9D9DE)

    /** Titles and body text. */
    val ink = Color(0xFF1C1C1E)

    /** Labels, captions, secondary detail, and the key labels. */
    val muted = Color(0xFF63636A)

    /** The one action colour: links, buttons, the control pill, the live tab. */
    val accent = Color(0xFF0A62E8)

    /** Text and icons drawn on [accent]. */
    val onAccent = Color(0xFFFFFFFF)

    /** Running, online, joined. */
    val ok = Color(0xFF137A3D)

    /** Waiting on a person. */
    val amber = Color(0xFF9A6300)

    /** Something failed, or an action that removes. */
    val danger = Color(0xFFC7162B)

    /** The tile behind a terminal glyph: the one place the app is dark. */
    val badge = Color(0xFF1C1C1E)

    /** The glyph on [badge]. */
    val badgeInk = Color(0xFF30D158)

    /** Behind a sheet. */
    val scrim = Color(0x40000000)

    /** Nothing drawn; the far end of an edge fade. A token rather than a literal at the call site. */
    val clear = Color(0x00000000)
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
 * Four sizes, four jobs: a screen title, a section or dialog title, body, and a label. Nothing else
 * is allowed, which is what keeps the app from collecting a dozen near-identical font sizes.
 */
object TextStyles {
    val display = 32.sp
    val title = 20.sp
    val body = 17.sp
    val label = 13.sp
}

/** Line height for body copy at [TextStyles.body]; Chinese needs a little more than Latin. */
val bodyLeading: TextUnit = 23.sp

/** Line height for multi-line labels at [TextStyles.label], such as a dialog's hint. */
val labelLeading: TextUnit = 18.sp

/**
 * The terminal's own paper and ink: white paper, dark ink, a blue cursor, so the pane belongs to the
 * same light surface as the rest of the app. Every palette entry stays the program's: only the
 * defaults a program never sets are bent to this app.
 */
fun terminalTheme(): GhosttyTheme = GhosttyTheme(
    foreground = Colors.ink.toArgb(),
    background = Colors.surface.toArgb(),
    cursorColor = Colors.accent.toArgb(),
    selectionBackground = Color(0xFFD5E3FB).toArgb(),
    selectionForeground = Colors.ink.toArgb(),
    palette = List(256) { null },
)

/**
 * Android has no media query for this: the animation scales are the platform's reduced-motion
 * switch, and an app that scrolls and animates should read them.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
