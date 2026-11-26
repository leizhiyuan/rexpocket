package me.leaver.rexpocket.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import me.leaver.rexpocket.R
import me.leaver.rexpocket.ui.Colors
import me.leaver.rexpocket.ui.Radius
import me.leaver.rexpocket.ui.TextStyles
import me.leaver.rexpocket.ui.labelLeading

// Pointing at a control: a modifier on the control, a layer per window that draws the spotlight,
// and the state both read. See docs/design/help-guide.md, "Pointing at a control".

/** What the spotlight points at, and what its card says. A tour step has its place in the tour. */
internal class Spotlight(
    val spot: GuideSpot,
    val title: String,
    val text: String,
    /** The tour's step, from 0, and its length; null for one entry's Show me. */
    val step: Int? = null,
    val steps: Int = 0,
)

/** A place for the screen that owns it to open; [hostId] for a host's own places. */
internal data class GuideRequest(val place: GuidePlace, val hostId: String? = null, val serial: Int = 0)

/** Why Help shows an entry's page instead of going there at once. */
internal enum class HelpNote { NeedsTab, NeedsHost, PickHost, CouldNotShow }

/** Help's sheet: the list (no entry) or one entry's page, with a note on what it needs first. */
internal data class HelpView(val entryId: String? = null, val note: HelpNote? = null)

/** What Show me, the tour or Open a tab asked GuideHost to do next. */
internal sealed interface GuideGo {
    data class Entry(val entry: GuideEntry, val hostId: String? = null) : GuideGo
    data class OpenTab(val entry: GuideEntry, val hostId: String) : GuideGo
    data object Tour : GuideGo
}

/**
 * Help's state, one per app: the sheet, the place a screen is asked to open, the spotlight, and
 * what the layers found. Screens read it through [LocalGuide]; none of it is saved, so a rotation
 * or a trip away ends a spotlight rather than bringing back a ring round something that moved.
 */
@Stable
internal class Guide {
    var help by mutableStateOf<HelpView?>(null)
    var request by mutableStateOf<GuideRequest?>(null)
    var spotlight by mutableStateOf<Spotlight?>(null)
        private set

    /** The spot a layer draws the spotlight on now: on screen, in view. */
    var shown by mutableStateOf<GuideSpot?>(null)
        internal set
    internal var pending by mutableStateOf<GuideGo?>(null)

    /** The tour's Next was asked for; GuideHost moves on and clears it. */
    internal var nextAsked by mutableIntStateOf(0)

    /** How many layers are composed: the root's, and one per open sheet. */
    internal var layers by mutableIntStateOf(0)
    private var serial = 0

    fun openHelp(entryId: String? = null, note: HelpNote? = null) {
        help = HelpView(entryId, note)
    }

    fun show(entry: GuideEntry, hostId: String? = null) {
        help = null
        pending = GuideGo.Entry(entry, hostId)
    }

    fun startTour() {
        help = null
        pending = GuideGo.Tour
    }

    internal fun ask(place: GuidePlace, hostId: String?) {
        request = GuideRequest(place, hostId, ++serial)
    }

    internal fun point(at: Spotlight?) {
        shown = null
        spotlight = at
    }

    /** Got it, Skip tour, a press outside the card, or the control going away. */
    fun dismiss() {
        point(null)
    }

    fun next() {
        nextAsked++
    }
}

/** The app's guide; null where there is none, which makes every [guideTarget] a no-op. */
internal val LocalGuide = staticCompositionLocalOf<Guide?> { null }

/** The window's layer a target reports to: the nearest [GuideLayer]. */
internal class LayerState {
    var coordinates: LayoutCoordinates? = null

    /** The spotlight's control in this layer's coordinates, while one of its targets is wanted. */
    var target by mutableStateOf<Pair<GuideSpot, Rect>?>(null)
}

internal val LocalGuideLayer = compositionLocalOf<LayerState?> { null }

/**
 * Marks the control Help may point at as [spot]. Inert unless the spotlight wants this spot: then
 * it is brought into view and its bounds go to its window's layer, which draws the ring.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.guideTarget(spot: GuideSpot?): Modifier = if (spot == null) this else this.then(
    Modifier.composed {
        val guide = LocalGuide.current
        val layer = LocalGuideLayer.current
        if (guide == null || layer == null) return@composed Modifier
        val wanted = guide.spotlight?.spot == spot
        val requester = remember { BringIntoViewRequester() }
        // Where the control was last laid out: kept, not reported, until it is wanted.
        val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
        fun report(coords: LayoutCoordinates) {
            val root = layer.coordinates?.takeIf { it.isAttached } ?: return
            if (!coords.isAttached) return
            val rect = root.localBoundingBoxOf(coords, clipBounds = true)
            if (rect.width > 0f && rect.height > 0f) layer.target = spot to rect
        }
        LaunchedEffect(wanted) {
            if (!wanted) return@LaunchedEffect
            // Once the sheet it is in has laid out, so its scroll knows how far it goes: frames, not
            // a time, which a frame clock that only moves while something draws never reaches.
            repeat(SETTLE_FRAMES) { withFrameNanos { } }
            runCatching { requester.bringIntoView() }
            placed[0]?.let(::report)
        }
        DisposableEffect(wanted) {
            onDispose {
                if (layer.target?.first == spot) layer.target = null
                // The control went (its sheet closed): the spotlight goes with it, not to wait for it.
                if (wanted && guide.shown == spot && guide.spotlight?.spot == spot) guide.dismiss()
            }
        }
        Modifier.bringIntoViewRequester(requester).onGloballyPositioned { coords ->
            placed[0] = coords
            if (wanted) report(coords)
        }
    },
)

/**
 * A window's content with the spotlight drawn over it when the control it wants is in this window.
 * The root of the app has one, and so does every sheet ([me.leaver.rexpocket.ui.ActionSheet]): a
 * sheet is a window of its own, which a ring drawn in the activity's would be under.
 */
@Composable
internal fun GuideLayer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val guide = LocalGuide.current
    if (guide == null) {
        Box(modifier) { content() }
        return
    }
    val layer = remember { LayerState() }
    DisposableEffect(guide) {
        guide.layers++
        onDispose { guide.layers-- }
    }
    val spotlight = guide.spotlight
    val found = layer.target?.takeIf { (spot, _) -> spot == spotlight?.spot }
    // The card's bounds, so a press on its buttons is not taken as a press elsewhere.
    var card by remember { mutableStateOf<Rect?>(null) }
    val showing = rememberUpdatedState(found != null)
    Box(
        modifier
            .onGloballyPositioned { layer.coordinates = it }
            // Watched on the way down and never consumed: the press goes on to whatever it landed
            // on, so a tap on the control both ends the spotlight and works. Always in the chain,
            // so the spotlight going mid-press changes nothing for the press.
            .pointerInput(guide) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!showing.value || event.type != PointerEventType.Press) continue
                        val at = event.changes.firstOrNull()?.position ?: continue
                        if (card?.contains(at) != true) guide.dismiss()
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalGuideLayer provides layer) { content() }
        if (found != null && spotlight != null) {
            LaunchedEffect(spotlight, found.first) { guide.shown = found.first }
            SpotlightOverlay(spotlight, found.second, guide, layer, onCard = { card = it })
        }
    }
}

/** The ring, the dimmed screen round it, and the card; drawing only, it takes no touch itself. */
@Composable
private fun SpotlightOverlay(spotlight: Spotlight, target: Rect, guide: Guide, layer: LayerState, onCard: (Rect) -> Unit) {
    val view = LocalView.current
    // Back ends the spotlight, or the tour, before it does anything else: the screen under it
    // (the home, which would leave the app) only gets the next one. Composed with the spotlight,
    // so it is the last handler added, which is asked first.
    BackHandler { guide.dismiss() }
    LaunchedEffect(spotlight) {
        // TalkBack says it as it appears; the card is then the first node, with its buttons.
        @Suppress("DEPRECATION")
        view.announceForAccessibility(spotlight.title + ". " + spotlight.text)
    }
    // Three beats, then a still ring: finite, so nothing keeps the frame clock busy after.
    val pulse = remember(spotlight) { Animatable(0f) }
    LaunchedEffect(spotlight) {
        repeat(PULSES) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(PULSE_MS))
        }
        pulse.snapTo(0f)
    }
    val scrim = Colors.scrim
    val ring = Colors.accent
    val density = view.resources.displayMetrics.density
    val pad = RING_PAD.value * density
    val hole = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
    Canvas(Modifier.fillMaxSize().testTag(SPOTLIGHT_TAG)) {
        val radius = CornerRadius(RING_RADIUS.toPx())
        val cut = Path().apply { addRoundRect(RoundRect(hole, radius)) }
        clipPath(cut, ClipOp.Difference) { drawRect(scrim.copy(alpha = SCRIM_ALPHA)) }
        val width = 3.dp.toPx()
        drawRoundRect(ring, Offset(hole.left, hole.top), Size(hole.width, hole.height), radius, style = Stroke(width))
        val beat = pulse.value
        if (beat > 0f) {
            val grow = 10.dp.toPx() * beat
            drawRoundRect(
                ring.copy(alpha = (1f - beat) * 0.8f),
                Offset(hole.left - grow, hole.top - grow),
                Size(hole.width + 2 * grow, hole.height + 2 * grow),
                CornerRadius(RING_RADIUS.toPx() + grow),
                style = Stroke(width),
            )
        }
    }
    // The card under the control when there is room, over it otherwise, inside what the window
    // shows: not behind the keyboard, which is up over a key bar's key or the pane, nor the bars.
    val safe = WindowInsets.safeDrawing
    Layout(
        content = { SpotlightCard(spotlight, guide) },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val width = minOf(constraints.maxWidth - 2 * margin, CARD_MAX.roundToPx()).coerceAtLeast(0)
        val placeable = measurables.single().measure(Constraints(maxWidth = width, maxHeight = constraints.maxHeight))
        // The layer's top in its window, to put the window's insets in the layer's terms.
        val coords = layer.coordinates?.takeIf { it.isAttached }
        val offset = coords?.positionInWindow()?.y?.toInt() ?: 0
        val window = coords?.findRootCoordinates()?.size?.height ?: (offset + constraints.maxHeight)
        val top = (safe.getTop(this) - offset).coerceIn(0, constraints.maxHeight)
        val bottom = (window - safe.getBottom(this) - offset).coerceIn(top, constraints.maxHeight)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val y = cardTop(hole.top, hole.bottom, placeable.height, top, bottom, margin, 12.dp.roundToPx())
            val x = (hole.center.x.toInt() - placeable.width / 2).coerceIn(margin, (constraints.maxWidth - margin - placeable.width).coerceAtLeast(margin))
            onCard(Rect(x.toFloat(), y.toFloat(), (x + placeable.width).toFloat(), (y + placeable.height).toFloat()))
            placeable.place(x, y)
        }
    }
}

/**
 * The top of a card [height] tall for a control from [holeTop] to [holeBottom], in a layer whose
 * shown part runs from [top] to [bottom]: under the control when it fits there, over it when it
 * fits there, else at the bottom of what is shown.
 */
internal fun cardTop(holeTop: Float, holeBottom: Float, height: Int, top: Int, bottom: Int, margin: Int, gap: Int): Int {
    val below = holeBottom.toInt() + gap
    val above = holeTop.toInt() - gap - height
    return when {
        below + height <= bottom - margin -> below
        above >= top + margin -> above
        else -> (bottom - height - margin).coerceAtLeast(top)
    }
}

@Composable
private fun SpotlightCard(spotlight: Spotlight, guide: Guide) {
    Column(
        Modifier.widthIn(max = CARD_MAX).clip(RoundedCornerShape(Radius.card)).background(Colors.raised)
            .testTag(SPOTLIGHT_CARD_TAG)
            // TalkBack's first stop: a group of its own put before the screen it lies over (in its
            // window's layer, so a sheet's card leads the sheet), so a swipe after the announcement
            // goes to its words and buttons rather than through every row first.
            .semantics { isTraversalGroup = true; traversalIndex = -1f }
            // The card is the spotlight's own: a press on its words stops here, rather than going
            // through to the row under it. Its buttons still take theirs.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(spotlight.title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.heightIn(min = 4.dp))
        Text(spotlight.text, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading, modifier = Modifier.padding(end = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            val step = spotlight.step
            if (step == null) {
                CardButton(stringResource(R.string.guide_got_it)) { guide.dismiss() }
            } else {
                CardButton(stringResource(R.string.guide_tour_skip), muted = true) { guide.dismiss() }
                CardButton(stringResource(if (step + 1 >= spotlight.steps) R.string.guide_tour_done else R.string.guide_tour_next)) { guide.next() }
            }
        }
    }
}

@Composable
private fun CardButton(label: String, muted: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = if (muted) Colors.muted else Colors.accent, fontWeight = FontWeight.SemiBold, fontSize = TextStyles.label)
    }
}

/**
 * Opens the [places] this screen owns when Help asks for one, with the screen's own state: one
 * block per screen, in HomeScreen and TermScreen.
 */
@Composable
internal fun GuideOpens(places: Set<GuidePlace>, open: (GuideRequest) -> Unit) {
    val guide = LocalGuide.current ?: return
    val request = guide.request
    LaunchedEffect(request) {
        if (request != null && request.place in places) {
            open(request)
            if (guide.request == request) guide.request = null
        }
    }
}

internal const val SPOTLIGHT_TAG = "guide-spotlight"
internal const val SPOTLIGHT_CARD_TAG = "guide-spotlight-card"

/** Frames for a sheet to have laid its rows out before its scroll is asked to move. */
private const val SETTLE_FRAMES = 2
private const val PULSES = 3
private const val PULSE_MS = 700
private const val SCRIM_ALPHA = 0.35f
private val RING_PAD = 4.dp
private val RING_RADIUS = 14.dp
private val CARD_MAX = 360.dp
