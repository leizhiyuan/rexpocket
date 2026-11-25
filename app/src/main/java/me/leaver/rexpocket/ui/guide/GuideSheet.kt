package me.leaver.rexpocket.ui.guide

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PaletteEntry
import me.leaver.rexpocket.core.rankPalette
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.ui.ActionSheet
import me.leaver.rexpocket.ui.Colors
import me.leaver.rexpocket.ui.Glyph
import me.leaver.rexpocket.ui.Group
import me.leaver.rexpocket.ui.SheetItem
import me.leaver.rexpocket.ui.SheetScope
import me.leaver.rexpocket.ui.TextStyles
import me.leaver.rexpocket.ui.labelLeading

// Help: a search over the entries, the entries by section, and an entry's page with Show me. See
// docs/design/help-guide.md, "How a person uses it".

internal const val GUIDE_SEARCH_TAG = "guide-search"
internal const val GUIDE_SHOW_ME_TAG = "guide-show-me"
internal fun guideEntryTag(id: String) = "guide-entry-$id"

/** Every string an entry is found by: its own, and in English too, as people type `claude` or `F10`. */
@Composable
internal fun guideWords(): Map<String, String> {
    val context = LocalContext.current
    return remember(context) {
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        GUIDE_ENTRIES.associate { e ->
            val own = listOf(e.keywords, e.body).joinToString(" ") { context.getString(it) }
            val words = if (english.getString(e.title) == context.getString(e.title)) own
            else own + " " + listOf(e.title, e.hint, e.keywords).joinToString(" ") { english.getString(it) }
            e.id to words
        }
    }
}

@Composable
internal fun GuideSheet(guide: Guide, view: HelpView, hosts: List<SavedHost>, terminalReady: Boolean) {
    val entry = view.entryId?.let(::guideEntry)
    ActionSheet(
        onDismiss = { guide.help = null },
        title = entry?.let { stringResource(it.title) } ?: stringResource(R.string.guide_title),
        onBack = if (entry != null) ({ guide.help = HelpView() }) else null,
    ) {
        if (entry == null) HelpList(guide) else EntryPage(guide, entry, view.note, hosts, terminalReady)
    }
}

@Composable
private fun SheetScope.HelpList(guide: Guide) {
    var query by rememberSaveable { mutableStateOf("") }
    val words = guideWords()
    SearchField(query) { query = it }
    val open: (GuideEntry) -> Unit = { guide.help = HelpView(it.id) }
    if (query.isBlank()) {
        Group {
            SheetItem(
                stringResource(R.string.guide_tour_row),
                icon = R.drawable.ic_touch_app,
                supporting = stringResource(R.string.guide_tour_row_detail),
            ) { guide.startTour() }
        }
        for (section in GuideSection.entries) {
            val entries = GUIDE_ENTRIES.filter { it.section == section }
            if (entries.isEmpty()) continue
            Group(stringResource(section.title), icons = false) {
                for (e in entries) EntryRow(e, open)
            }
        }
    } else {
        val rows = GUIDE_ENTRIES.map { e ->
            PaletteEntry(stringResource(e.title), stringResource(e.section.title), stringResource(e.hint), e, keywords = words.getValue(e.id))
        }
        val shown = rankPalette(rows, query)
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.guide_nothing_found, query.trim()),
                color = Colors.muted,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Group(icons = false) {
                for (row in shown) EntryRow(row.value, open)
            }
        }
    }
}

@Composable
private fun SheetScope.EntryRow(entry: GuideEntry, open: (GuideEntry) -> Unit) {
    SheetItem(
        stringResource(entry.title),
        supporting = stringResource(entry.hint),
        opens = true,
        keepOpen = true,
        modifier = Modifier.testTag(guideEntryTag(entry.id)),
    ) { open(entry) }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Colors.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_search, Colors.muted, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.guide_search_field), color = Colors.muted, fontSize = TextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                cursorBrush = SolidColor(Colors.accent),
                modifier = Modifier.fillMaxWidth().testTag(GUIDE_SEARCH_TAG),
            )
        }
    }
}

/**
 * An entry's page: what it is and how, then Show me - or, when its place needs something first,
 * what that is and the way to it: a tab on screen, a host, or which host.
 */
@Composable
private fun SheetScope.EntryPage(guide: Guide, entry: GuideEntry, note: HelpNote?, hosts: List<SavedHost>, terminalReady: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(entry.hint), color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(stringResource(entry.body), color = Colors.muted, fontSize = TextStyles.subhead, lineHeight = labelLeading)
    }
    val needs = when {
        entry.place.terminal && !terminalReady -> HelpNote.NeedsTab
        entry.place.needsHost && hosts.isEmpty() -> HelpNote.NeedsHost
        entry.place.needsHost && hosts.size > 1 -> HelpNote.PickHost
        else -> null
    }
    if (note == HelpNote.CouldNotShow && needs == null) Note(stringResource(R.string.guide_could_not_show))
    val addHost = guideEntry("add_host")
    when (needs) {
        HelpNote.NeedsTab -> {
            Note(stringResource(R.string.guide_needs_tab))
            Group {
                for (host in hosts.take(MAX_HOSTS)) {
                    SheetItem(stringResource(R.string.guide_open_tab_on, host.label), Colors.accent, icon = R.drawable.ic_sessions) {
                        guide.pending = GuideGo.OpenTab(entry, host.id)
                    }
                }
                if (hosts.isEmpty() && addHost != null) {
                    SheetItem(stringResource(addHost.title), Colors.accent, icon = R.drawable.ic_add) { guide.show(addHost) }
                }
            }
        }
        HelpNote.NeedsHost -> {
            Note(stringResource(R.string.guide_needs_host))
            if (addHost != null) Group { SheetItem(stringResource(addHost.title), Colors.accent, icon = R.drawable.ic_add) { guide.show(addHost) } }
        }
        HelpNote.PickHost -> {
            Note(stringResource(R.string.guide_pick_host))
            Group {
                for (host in hosts) SheetItem(host.label, icon = R.drawable.ic_host) { guide.show(entry, host.id) }
            }
        }
        else -> Group {
            SheetItem(
                stringResource(R.string.guide_show_me),
                Colors.accent,
                icon = R.drawable.ic_touch_app,
                modifier = Modifier.testTag(GUIDE_SHOW_ME_TAG),
            ) { guide.show(entry) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Colors.ink, fontSize = TextStyles.label, lineHeight = labelLeading, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
}

/** The hosts Open a tab offers; past these, the home is the place to pick one. */
private const val MAX_HOSTS = 4
