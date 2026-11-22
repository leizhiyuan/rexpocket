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
import androidx.test.core.app.ApplicationProvider
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
 * key went.
 */
@Composable
internal fun ControlsOnModel(model: RexViewModel, page: ControlsPage?, onPage: (ControlsPage?) -> Unit, bar: Boolean = true) {
    val arrangement by model.keyArrangement.collectAsState()
    val gestures by model.gestures.collectAsState()
    val keyBar by model.keyBar.collectAsState()
    val ctrlC by model.keyBarCtrlC.collectAsState()
    RexTheme {
        if (bar) {
            Box(Modifier.wrapContentWidth(unbounded = true).requiredWidth(891.dp)) {
                KeyBar(
                    mods = KeyModifiers(),
                    busy = false,
                    imeUp = false,
                    onMod = {},
                    onKey = { _, _ -> },
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
