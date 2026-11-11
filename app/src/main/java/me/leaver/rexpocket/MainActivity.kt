package me.leaver.rexpocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.ui.RexPocketApp
import me.leaver.rexpocket.ui.RexViewModel

class MainActivity : ComponentActivity() {
    private val model: RexViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The journal that holds the last few hours, a recording the last process left running (a
        // bug that kills the app is the one worth recording), and the crash handler that writes a
        // failure into it before the app goes.
        Diagnostics.install(this)
        // A tapped alert opens its pane; a recreated activity has already acted on its intent.
        if (savedInstanceState == null) Alerts.consume(intent)?.let(model::openAlert)
        setContent { RexPocketApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Alerts.consume(intent)?.let(model::openAlert)
    }

    // Leaving the app is the usual trigger for a tunnel that has to find its way back, so the
    // moment it happens belongs in the diagnostics next to the node's own log.
    override fun onStart() {
        super.onStart()
        Diagnostics.event(LIFECYCLE_TAG, "foreground")
    }

    override fun onStop() {
        Diagnostics.event(LIFECYCLE_TAG, "background")
        super.onStop()
    }
}

private const val LIFECYCLE_TAG = "RexPocketLife"
