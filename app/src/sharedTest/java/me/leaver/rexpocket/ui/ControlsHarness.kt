package me.leaver.rexpocket.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.KeyEvent
import me.leaver.rexpocket.core.KeyModifiers

// What the settings screen tests show: the app's own pages, on the app's own view model and store.
// See docs/design/testing.md: a test that drives a copy of the state proves the copy works.

/** The app, with nothing stored yet: the preferences every setting is read from, emptied. */
internal fun freshApp(): Application {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit().clear().commit()
    return app
}

/**
 * Settings › Terminal controls as HomeScreen shows it, fed as RexApp feeds it: each value collected
 * from [model], each change handed to it. With [bar], the key bar that the terminal draws from the
 * same arrangement sits under the sheet, wide enough for every key, so a test can read where each
 * key went. The bar holds its modifiers as the terminal screen does, and hands each key it sends to
 * [onKey] (as Rex is sent it) or [onDeskKey] (with the modifiers held for it).
 */
@Composable
internal fun ControlsOnModel(
    model: RexViewModel,
    page: ControlsPage?,
    onPage: (ControlsPage?) -> Unit,
    bar: Boolean = true,
    onKey: (KeyEvent) -> Unit = {},
    onDeskKey: (DeskKey, KeyModifiers) -> Unit = { _, _ -> },
) {
    var mods by remember { mutableStateOf(KeyModifiers()) }
    val arrangement by model.keyArrangement.collectAsState()
    val gestures by model.gestures.collectAsState()
    val keyBar by model.keyBar.collectAsState()
    val ctrlC by model.keyBarCtrlC.collectAsState()
    RexTheme {
        if (bar) {
            Box(Modifier.wrapContentWidth(unbounded = true).requiredWidth(891.dp)) {
                KeyBar(
                    mods = mods,
                    busy = false,
                    imeUp = false,
                    onMod = { mods = mods.tap(it, 0L) },
                    onKey = { key, own ->
                        onKey(mods.event(key, own))
                        mods = mods.consume()
                    },
                    onDeskKey = {
                        onDeskKey(it, mods)
                        mods = mods.consume()
                    },
                    onNewLine = {},
                    onPaste = {},
                    onAttach = {},
                    attachReady = true,
                    onKeyboard = {},
                    arrangement = arrangement,
                    ctrlC = ctrlC,
                    onFullScreen = {},
                )
            }
        }
        TerminalControlsPages(
            page = page,
            onPage = onPage,
            gestures = gestures,
            onGestures = model::setGestures,
            keyBar = keyBar,
            onKeyBar = model::setKeyBar,
            keyBarCtrlC = ctrlC,
            onKeyBarCtrlC = model::setKeyBarCtrlC,
            keyArrangement = arrangement,
            onKeyArrangement = model::setKeyArrangement,
        )
    }
}

/** The option called [option] in the picker that is open: its radio row, words and description in one node. */
internal fun AppRule.pickerOption(option: String): SemanticsNodeInteraction =
    onNode(hasText(option) and isSelectable() and hasAnyAncestor(hasTestTag(PICKER_TAG)))

/**
 * Opens the picker of the sheet row called [label], as a tap on it does, and picks [option] there.
 * The picker closes on the pick; the sheet under it stays.
 */
internal fun AppRule.pick(label: String, option: String) {
    onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    waitForIdle()
    pickerOption(option).performClick()
    waitForIdle()
    onNode(hasTestTag(PICKER_TAG)).assertDoesNotExist()
}
