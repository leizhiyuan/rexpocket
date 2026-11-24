package me.leaver.rexpocket.data

import android.content.Context
import me.leaver.rexpocket.core.LaunchTemplate
import org.json.JSONArray

/**
 * Each host's launch templates (docs/design/home.md, "Launch templates"), in the person's order:
 * one JSON array per host id, in a file of their own. A host that is removed keeps its templates
 * until its Undo has gone by, so the Undo brings them back with it.
 */
class LaunchTemplateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("launch_templates", Context.MODE_PRIVATE)

    fun all(): Map<String, List<LaunchTemplate>> = prefs.all.mapNotNull { (hostId, value) ->
        val list = (value as? String)?.let { runCatching { LaunchTemplate.decode(JSONArray(it)) }.getOrNull() }.orEmpty()
        if (list.isEmpty()) null else hostId to list
    }.toMap()

    fun set(hostId: String, templates: List<LaunchTemplate>) {
        val edit = prefs.edit()
        if (templates.isEmpty()) edit.remove(hostId) else edit.putString(hostId, LaunchTemplate.encode(templates.take(LaunchTemplate.MAX_ITEMS)).toString())
        edit.apply()
    }
}
