package me.leaver.rexpocket.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.leaver.rexpocket.core.ProgramMark
import me.leaver.rexpocket.core.programMark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The tile that says what runs in a pane, as the desktop's sidebar does: a light plate with the
// program's own mark for an agent or a tool the app knows, the dark terminal tile for a shell, and
// the program's initial for anything else. The marks are the app's own drawings, not the products'
// logos: one 24-unit grid, one stroke weight, so they read as a family beside the platform icons.

/** The tile for [program], a name such as `pi` or `zsh`; null is a pane with nothing reported yet. */
@Composable
internal fun ProgramTile(program: String?, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val mark = programMark(program)
    if (mark == ProgramMark.Shell) {
        TerminalTile(modifier, size)
        return
    }
    val shape = RoundedCornerShape(size * 0.27f)
    Box(
        modifier.size(size).clip(shape).background(Colors.tile).border(1.dp, Colors.edge, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (mark == ProgramMark.Other) {
            Text(
                program.orEmpty().trim().removePrefix("-").take(1).uppercase(),
                color = Colors.tileInk,
                fontSize = (size.value * 0.42f).sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        } else {
            val ink = Colors.tileInk
            val accent = markAccent(mark)
            Canvas(Modifier.size(size * 0.62f)) { drawMark(mark, ink, accent) }
        }
    }
}

/** The one colour a mark may carry beside its ink; most carry none. */
private fun markAccent(mark: ProgramMark): Color = when (mark) {
    ProgramMark.Claude -> Color(0xFFD97757)
    ProgramMark.Gemini -> Color(0xFF4285F4)
    ProgramMark.Node -> Color(0xFF3C873A)
    ProgramMark.Python -> Color(0xFF3776AB)
    ProgramMark.Docker -> Color(0xFF1D63ED)
    ProgramMark.Git -> Color(0xFFF05032)
    ProgramMark.Vim -> Color(0xFF019733)
    else -> Color.Unspecified
}

/** Draws [mark] in a square canvas, on a 24-unit grid. */
private fun DrawScope.drawMark(mark: ProgramMark, ink: Color, accent: Color) {
    val u = size.width / 24f
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = ink) =
        drawLine(color, p(x1, y1), p(x2, y2), 2f * u, StrokeCap.Round)
    fun path(color: Color = ink, build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = stroke)
    val tint = if (accent == Color.Unspecified) ink else accent
    when (mark) {
        // π, the agent's own letter: a bar and two legs, the right one turned out.
        ProgramMark.Pi -> {
            line(4f, 7f, 20f, 7f)
            line(9f, 7f, 8f, 19f)
            path {
                moveTo(15f * u, 7f * u)
                lineTo(15f * u, 16.5f * u)
                quadraticTo(15f * u, 19f * u, 18f * u, 19f * u)
            }
        }
        // A burst of rays, in the agent's warm orange.
        ProgramMark.Claude -> {
            for (i in 0 until 8) {
                val a = (i * PI / 4).toFloat()
                val inner = if (i % 2 == 0) 2.5f else 3.5f
                line(12f + cos(a) * inner, 12f + sin(a) * inner, 12f + cos(a) * 9f, 12f + sin(a) * 9f, tint)
            }
        }
        // An open ring with a prompt in it.
        ProgramMark.Codex -> {
            drawArc(ink, -60f, 300f, false, p(3f, 3f), Size(18f * u, 18f * u), style = stroke)
            path {
                moveTo(8.5f * u, 9.5f * u)
                lineTo(11f * u, 12f * u)
                lineTo(8.5f * u, 14.5f * u)
            }
            line(13f, 14.5f, 16f, 14.5f)
        }
        // A four-point star.
        ProgramMark.Gemini -> path(tint) {
            moveTo(12f * u, 3f * u)
            quadraticTo(12.6f * u, 11.4f * u, 21f * u, 12f * u)
            quadraticTo(12.6f * u, 12.6f * u, 12f * u, 21f * u)
            quadraticTo(11.4f * u, 12.6f * u, 3f * u, 12f * u)
            quadraticTo(11.4f * u, 11.4f * u, 12f * u, 3f * u)
            close()
        }
        // A cube seen from its corner.
        ProgramMark.Cursor -> {
            path {
                moveTo(12f * u, 3f * u)
                lineTo(20f * u, 7.5f * u)
                lineTo(20f * u, 16.5f * u)
                lineTo(12f * u, 21f * u)
                lineTo(4f * u, 16.5f * u)
                lineTo(4f * u, 7.5f * u)
                close()
            }
            line(4f, 7.5f, 12f, 12f)
            line(20f, 7.5f, 12f, 12f)
            line(12f, 12f, 12f, 21f)
        }
        // A frame with a filled block in its lower half.
        ProgramMark.OpenCode -> {
            drawRoundRect(ink, p(5f, 3f), Size(14f * u, 18f * u), CornerRadius(1.5f * u), style = stroke)
            drawRect(ink, p(8f, 12f), Size(8f * u, 6f * u))
        }
        // A face with two eyes, the pair-programmer.
        ProgramMark.Copilot -> {
            drawRoundRect(ink, p(3f, 6f), Size(18f * u, 13f * u), CornerRadius(5f * u), style = stroke)
            drawCircle(ink, 1.6f * u, p(9f, 12.5f))
            drawCircle(ink, 1.6f * u, p(15f, 12.5f))
        }
        // The editor's V, on a green diamond's corner.
        ProgramMark.Vim -> {
            path(tint) {
                moveTo(12f * u, 2.5f * u)
                lineTo(21.5f * u, 12f * u)
                lineTo(12f * u, 21.5f * u)
                lineTo(2.5f * u, 12f * u)
                close()
            }
            path {
                moveTo(8f * u, 8f * u)
                lineTo(12f * u, 16f * u)
                lineTo(16f * u, 8f * u)
            }
        }
        // An E in a ring.
        ProgramMark.Emacs -> {
            drawCircle(ink, 9f * u, p(12f, 12f), style = stroke)
            path {
                moveTo(15f * u, 8f * u)
                lineTo(9f * u, 8f * u)
                lineTo(9f * u, 16f * u)
                lineTo(15f * u, 16f * u)
            }
            line(9f, 12f, 13.5f, 12f)
        }
        // Containers stacked on a hull.
        ProgramMark.Docker -> {
            for ((x, y) in listOf(5f to 10f, 9f to 10f, 13f to 10f, 9f to 6f)) {
                drawRoundRect(tint, p(x, y), Size(3.2f * u, 3.2f * u), CornerRadius(0.6f * u))
            }
            path(tint) {
                moveTo(2.5f * u, 14.5f * u)
                lineTo(19f * u, 14.5f * u)
                quadraticTo(21f * u, 14.5f * u, 21.5f * u, 12.5f * u)
                moveTo(2.5f * u, 14.5f * u)
                quadraticTo(4f * u, 20f * u, 11f * u, 20f * u)
                quadraticTo(17f * u, 20f * u, 19f * u, 14.5f * u)
            }
        }
        // A hexagon.
        ProgramMark.Node -> path(tint) {
            for (i in 0..5) {
                val a = (PI / 6 + i * PI / 3).toFloat()
                val x = 12f + cos(a) * 9f
                val y = 12f + sin(a) * 9f
                if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u)
            }
            close()
        }
        // Two interlocking hooks.
        ProgramMark.Python -> {
            path(tint) {
                moveTo(12f * u, 11f * u)
                lineTo(7f * u, 11f * u)
                quadraticTo(4f * u, 11f * u, 4f * u, 14f * u)
                lineTo(4f * u, 16f * u)
                quadraticTo(4f * u, 19f * u, 7f * u, 19f * u)
                lineTo(9f * u, 19f * u)
                moveTo(9f * u, 11f * u)
                lineTo(9f * u, 7f * u)
                quadraticTo(9f * u, 4f * u, 12f * u, 4f * u)
                lineTo(15f * u, 4f * u)
                quadraticTo(18f * u, 4f * u, 18f * u, 7f * u)
                lineTo(18f * u, 10f * u)
            }
            path {
                moveTo(12f * u, 13f * u)
                lineTo(17f * u, 13f * u)
                quadraticTo(20f * u, 13f * u, 20f * u, 10f * u)
                moveTo(15f * u, 13f * u)
                lineTo(15f * u, 17f * u)
                quadraticTo(15f * u, 20f * u, 12f * u, 20f * u)
                lineTo(9f * u, 20f * u)
            }
        }
        // A branch leaving its trunk.
        ProgramMark.Git -> {
            line(7f, 4f, 7f, 20f, tint)
            path(tint) {
                moveTo(17f * u, 9f * u)
                quadraticTo(17f * u, 14f * u, 7f * u, 15f * u)
            }
            drawCircle(tint, 2.4f * u, p(17f, 7f), style = stroke)
            drawCircle(tint, 2.4f * u, p(7f, 4f))
            drawCircle(tint, 2.4f * u, p(7f, 20f))
        }
        // A remote machine: a screen with a prompt and a link.
        ProgramMark.Ssh -> {
            drawRoundRect(ink, p(3f, 4f), Size(18f * u, 13f * u), CornerRadius(2f * u), style = stroke)
            path {
                moveTo(7f * u, 8.5f * u)
                lineTo(9.5f * u, 10.5f * u)
                lineTo(7f * u, 12.5f * u)
            }
            line(12f, 20f, 12f, 17f)
            line(8f, 20f, 16f, 20f)
        }
        // Bars of a meter.
        ProgramMark.Top -> {
            line(6f, 19f, 6f, 13f)
            line(10f, 19f, 10f, 7f)
            line(14f, 19f, 14f, 10f)
            line(18f, 19f, 18f, 5f)
        }
        ProgramMark.Shell, ProgramMark.Other -> Unit
    }
}
