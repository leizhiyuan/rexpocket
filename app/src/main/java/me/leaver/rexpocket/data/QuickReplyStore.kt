package me.leaver.rexpocket.data

import android.content.Context
import me.leaver.rexpocket.core.QuickReplies

/**
 * The person's quick replies (Settings › Quick replies), kept with the app's other settings as one
 * JSON array under [KEY]. Read where they are used - a notification's Reply reads them as it is
 * posted - so a change shows everywhere at once.
 */
class QuickReplyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    fun get(): QuickReplies = QuickReplies.fromStored(prefs.getString(KEY, null))

    fun set(replies: QuickReplies) {
        prefs.edit().putString(KEY, replies.stored()).apply()
    }

    companion object {
        const val KEY = "quick_replies"
    }
}
