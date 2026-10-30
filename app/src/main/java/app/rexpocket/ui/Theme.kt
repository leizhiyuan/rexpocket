package app.rexpocket.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's one design system. Every colour, size and radius in the UI comes from here, so a screen
 * cannot drift: one anchor hue (a terminal green) on a green-tinted near-black, one type scale, one
 * spacing scale.
 *
 * Contrast, measured against [Colors.bg] and [Colors.surface]: ink 15.4 / 13.6, muted 6.0 / 5.4,
 * accent 13.8 / 12.2, amber 11.7 / 10.3, danger 6.3 / 5.5. Text on the accent is [Colors.onAccent].
 */
object Colors {
    /** Paper. Not #000: it carries the anchor hue at low chroma. */
    val bg = Color(0xFF10120F)

    /** A raised row or sheet. */
    val surface = Color(0xFF1C1F1A)

    /** Hairlines and inactive edges. Decoration only, never a control boundary. */
    val line = Color(0xFF2C3129)

    /** Body text and titles. */
    val ink = Color(0xFFE7E9E4)

    /** Labels, captions, secondary detail. */
    val muted = Color(0xFF8E9488)

    /** The one signal colour: live, joined, running. */
    val accent = Color(0xFF00FF41)

    /** Text and icons drawn on [accent]. */
    val onAccent = Color(0xFF10120F)

    /** Waiting on a person. */
    val amber = Color(0xFFF0C674)

    /** Something failed, or an action that removes. */
    val danger = Color(0xFFFF5D5D)

    /** Behind a sheet. */
    val scrim = Color(0x99000000)

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
    val chip = 10.dp
    val card = 14.dp
    val sheet = 22.dp
}

/**
 * Four sizes, four jobs: a screen title, a section or dialog title, body, and a label. Nothing else
 * is allowed, which is what keeps the app from collecting a dozen near-identical font sizes.
 */
object TextStyles {
    val display = 26.sp
    val title = 20.sp
    val body = 15.sp
    val label = 12.sp
}

/** Line height for body copy at [TextStyles.body]; Chinese needs a little more than Latin. */
val bodyLeading: TextUnit = 21.sp

/** Line height for multi-line labels at [TextStyles.label], such as a dialog's hint. */
val labelLeading: TextUnit = 17.sp

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
