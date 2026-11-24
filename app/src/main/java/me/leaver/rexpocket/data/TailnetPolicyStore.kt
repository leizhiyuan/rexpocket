package me.leaver.rexpocket.data

import android.content.Context
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.TailnetPolicies
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetUse
import org.json.JSONArray
import org.json.JSONObject

/**
 * Each tailnet's choices (when it is up, what it does in the background, its notifications) and
 * which tailnets the person disconnected by hand, beside the hosts' background choices. A tailnet
 * with the defaults has no entry. See docs/design/tailnet-control.md.
 */
class TailnetPolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    fun read(): TailnetPolicies {
        val o = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return TailnetPolicies()
        val policies = buildMap {
            val all = o.optJSONObject("policies") ?: JSONObject()
            for (id in all.keys()) {
                val p = all.optJSONObject(id) ?: continue
                put(
                    id,
                    TailnetPolicy(
                        use = TailnetUse.fromStorage(p.optString("use")),
                        lingerMs = if (p.isNull("lingerMs")) null else p.optLong("lingerMs", TailnetPolicy.DEFAULT_LINGER_MS),
                        keep = BackgroundKeep.fromStorage(p.optString("keep")),
                        notify = p.optBoolean("notify", true),
                    ),
                )
            }
        }
        val off = o.optJSONArray("off")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() }.orEmpty()
        return TailnetPolicies(policies, off)
    }

    fun write(next: TailnetPolicies) {
        val all = JSONObject()
        for ((id, p) in next.policies) {
            all.put(
                id,
                JSONObject()
                    .put("use", p.use.storageKey)
                    .put("lingerMs", p.lingerMs ?: JSONObject.NULL)
                    .put("keep", p.keep.storageKey)
                    .put("notify", p.notify),
            )
        }
        val o = JSONObject().put("policies", all).put("off", JSONArray(next.off.sorted()))
        // Committed: a boot or a restart without a screen reads this to decide what to start.
        prefs.edit().putString(KEY, o.toString()).commit()
    }

    private companion object {
        const val KEY = "tailnet.policies"
    }
}
