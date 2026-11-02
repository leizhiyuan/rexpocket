package me.leaver.rexpocket.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The marks and the round controls that carry them. Icons are Material Symbols Rounded, the
// platform's own set, as vector drawables in res/drawable: one grid, one weight, one corner, so a
// back arrow, a chevron and a keyboard read as one family at every size.

/** One icon, tinted; the control around it says what it does. */
@Composable
internal fun Glyph(@DrawableRes id: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/**
 * A round control: a [plate] of [size], the icon in it, and a touch target of at least 48 dp
 * however small the plate is drawn. Without a plate it is the platform's plain icon button.
 */
@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    spoken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    plate: Color? = null,
    tint: Color = Colors.ink,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier.minimumInteractiveComponentSize().size(size).clip(CircleShape)
            .then(if (plate != null) Modifier.background(plate) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, tint, size = iconSize)
    }
}

/**
 * The surface of a control that floats over content - a pill or a circle. A soft shadow in the day;
 * at night, where a shadow does not show, a lighter fill and a hairline edge do the same job.
 */
internal fun Modifier.floating(shape: androidx.compose.ui.graphics.Shape = CircleShape): Modifier =
    this.shadow(if (Colors.isDark) 0.dp else 6.dp, shape, clip = false, ambientColor = Colors.shadow, spotColor = Colors.shadow)
        .clip(shape)
        .background(Colors.raised)
        .border(1.dp, Colors.edge, shape)

/**
 * The terminal tile: a dark rounded square with a prompt on it, the one mark the app shares with a
 * desk terminal. The prompt is drawn, so it sits optically centred at every size.
 */
@Composable
internal fun TerminalTile(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(Colors.badge),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.5f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = (w * 0.12f).coerceAtLeast(1.5.dp.toPx())
            val chevron = Path().apply {
                moveTo(w * 0.12f, h * 0.24f)
                lineTo(w * 0.42f, h * 0.5f)
                lineTo(w * 0.12f, h * 0.76f)
            }
            drawPath(chevron, Colors.badgeInk, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(Colors.badgeInk, Offset(w * 0.54f, h * 0.78f), Offset(w * 0.9f, h * 0.78f), stroke, cap = StrokeCap.Round)
        }
    }
}

/** A status dot; with a [ring] it is cut out of what it sits on, as a badge on a tile is. */
@Composable
internal fun Dot(color: Color, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    if (ring == null) {
        Box(modifier.size(size).clip(CircleShape).background(color))
    } else {
        Box(modifier.size(size + 4.dp).clip(CircleShape).background(ring).padding(2.dp).clip(CircleShape).background(color))
    }
}
