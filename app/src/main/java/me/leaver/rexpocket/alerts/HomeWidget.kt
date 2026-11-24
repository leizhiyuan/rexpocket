package me.leaver.rexpocket.alerts

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SeenCheck
import me.leaver.rexpocket.core.activitySections
import me.leaver.rexpocket.core.markCovers
import me.leaver.rexpocket.core.runningItems
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.ui.Connections
import org.json.JSONArray
import org.json.JSONObject

// The home screen widget: per connected host, how many tabs need the person, are done (not yet
// seen) and are working, in the home's words. Only a live connection hears a host's programs, so
// the counts are as fresh as the background watch keeps them: it writes them as they change; with
// background mode off they are the ones from when the app left the screen, and the widget says so.
// See docs/design/notifications.md, "Widget".

/** One host's counts, as the widget shows them. */
internal data class WidgetHost(val name: String, val needs: Int, val done: Int, val working: Int)

/** What the widget shows: the hosts' counts, when they were read, and whether the watch keeps them fresh. */
internal data class WidgetSnapshot(val at: Long, val live: Boolean, val hosts: List<WidgetHost>) {
    fun toJson(): String = JSONObject().put("at", at).put("live", live).put(
        "hosts",
        JSONArray().apply { hosts.forEach { put(JSONObject().put("name", it.name).put("needs", it.needs).put("done", it.done).put("working", it.working)) } },
    ).toString()

    companion object {
        fun parse(text: String?): WidgetSnapshot? {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val arr = o.optJSONArray("hosts") ?: JSONArray()
            val hosts = (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                WidgetHost(h.optString("name"), h.optInt("needs"), h.optInt("done"), h.optInt("working"))
            }
            return WidgetSnapshot(o.optLong("at"), o.optBoolean("live"), hosts)
        }
    }
}

/**
 * A host's counts: the tabs waiting on the person or failed (Needs you), done and not yet seen,
 * and working - as the home's Activity groups them ([activitySections], [runningItems]).
 */
internal fun widgetCounts(name: String, state: PocketState, seen: SeenCheck): WidgetHost {
    val sections = activitySections(state, seen)
    fun count(vararg groups: ActivityGroup) = sections.filter { it.group in groups }.sumOf { it.items.size }
    return WidgetHost(name, count(ActivityGroup.Waiting, ActivityGroup.Failed), count(ActivityGroup.Done), runningItems(state, seen).size)
}

class HomeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshot.parse(prefs(context).getString(KEY, null))
        provideContent { GlanceTheme { Content(context, snapshot) } }
    }

    @Composable
    private fun Content(context: Context, snapshot: WidgetSnapshot?) {
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val ink = GlanceTheme.colors.onSurface
        val muted = GlanceTheme.colors.onSurfaceVariant
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)
                .clickable(actionStartActivity(open)),
        ) {
            Text(context.getString(R.string.app_name), style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(4.dp))
            when {
                snapshot == null -> Text(context.getString(R.string.widget_never), style = TextStyle(color = muted, fontSize = 12.sp))
                snapshot.hosts.isEmpty() -> Text(context.getString(R.string.widget_no_hosts), style = TextStyle(color = muted, fontSize = 12.sp))
                else -> for (host in snapshot.hosts.take(MAX_HOSTS)) {
                    val counts = context.getString(R.string.widget_counts, host.needs, host.done, host.working)
                    // One line for TalkBack: the host, then its counts.
                    Column(GlanceModifier.padding(vertical = 2.dp).semantics { contentDescription = "${host.name}: $counts" }) {
                        Text(host.name, style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                        Text(counts, style = TextStyle(color = if (host.needs > 0) GlanceTheme.colors.primary else muted, fontSize = 12.sp), maxLines = 1)
                    }
                }
            }
            if (snapshot != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(footer(context, snapshot), style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 2)
            }
        }
    }

    companion object {
        /** Opens the home on Activity: the widget's tap. */
        const val ACTION_OPEN_ACTIVITY = "me.leaver.rexpocket.OPEN_ACTIVITY"
        private const val KEY = "widget.snapshot"
        private const val MAX_HOSTS = 4
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

        /** When the counts were read, and with background mode off that they may be stale. */
        internal fun footer(context: Context, snapshot: WidgetSnapshot): String {
            val time = DateUtils.formatSameDayTime(snapshot.at, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString()
            return context.getString(if (snapshot.live) R.string.widget_updated else R.string.widget_stale, time)
        }

        /** The counts [connections] have now, with [live] true while the background watch keeps them fresh. */
        internal fun snapshotOf(connections: Connections, live: Boolean, at: Long = System.currentTimeMillis()): WidgetSnapshot {
            val marks = connections.marks.seen.value
            val hosts = connections.lives.value.values.map { h ->
                val seen: SeenCheck = { block, record -> markCovers(marks[HomeMarks.key(h.target.id, block)], record) }
                widgetCounts(h.target.label.ifBlank { h.target.host }, h.state, seen)
            }
            return WidgetSnapshot(at, live, hosts)
        }

        /**
         * Writes the counts [connections] have and redraws the widgets, when there are any: from the
         * background watch as they change ([background] true), and when it stops or the app leaves
         * the screen without it ([background] false). A change in the counts only, not in the time,
         * redraws while the watch runs.
         */
        fun update(context: Context, connections: Connections, background: Boolean) {
            val app = context.applicationContext
            val next = snapshotOf(connections, background)
            val before = WidgetSnapshot.parse(prefs(app).getString(KEY, null))
            if (before != null && before.hosts == next.hosts && before.live == next.live && background) return
            prefs(app).edit().putString(KEY, next.toJson()).apply()
            scope.launch {
                runCatching {
                    if (GlanceAppWidgetManager(app).getGlanceIds(HomeWidget::class.java).isNotEmpty()) HomeWidget().updateAll(app)
                }
            }
        }
    }
}

/** The widget's receiver, which Android's launcher talks to. */
class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HomeWidget()
}
