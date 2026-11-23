package me.leaver.rexpocket

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The share target. It only hands the share to [MainActivity] in the app's own task, so a share
 * from another app reaches the one view model and connection there instead of starting a second
 * app inside the sender's task. The read grant for the shared URIs goes along (they are in the
 * intent's clip data); nothing is sent until the person taps Send on the sheet MainActivity shows.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = Intent(intent).setClass(this, MainActivity::class.java)
        share.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION
        startActivity(share)
        finish()
    }
}
