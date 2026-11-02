package me.leaver.rexpocket

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import me.leaver.rexpocket.ui.RexPocketApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app is light from the first frame, so the system's own marks are dark on it.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { RexPocketApp() }
    }

    // Leaving the app is the usual trigger for a tunnel that has to find its way back, so the
    // moment it happens belongs in the diagnostics next to the node's own log.
    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEBUG) Log.i(LIFECYCLE_TAG, "foreground")
    }

    override fun onStop() {
        if (BuildConfig.DEBUG) Log.i(LIFECYCLE_TAG, "background")
        super.onStop()
    }
}

private const val LIFECYCLE_TAG = "RexPocketLife"
