package me.leaver.rexpocket.core

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's own Rex actions - `rex.action{...}` in Rex's configuration file - run from the
 * phone's palette, and the questions they ask with `rex.ui.input` and `rex.ui.pick` answered on the
 * phone. See docs/design/command-palette.md, "Rex's custom actions", and rex-parity.md.
 *
 * Shapes, as a real Rex sends them (preview.22, api 19; `RexActionsTest` holds them):
 * - `client.keymap` lists each configuration action with `kind: "lua"`, `name`, `title`, and
 *   `category` ("Actions" when the file gives none), `description`, `keywords`, `source` and `args`
 *   (a JSON Schema, whose `required` lists what must be given) when it has them.
 * - `client.action {action, origin: "palette", session_id, block_id}` answers `{result,
 *   client_actions}` once the action returns, however long its questions take.
 * - A question is a `client_prompt_requested` event `{call_id, action, prompt: {kind: "input" |
 *   "pick", title, placeholder, value, confirm, multiple, allow_other, items: [{id, title,
 *   subtitle, icon, detail, selected}]}, timeout_ms}`, answered with `client.reply {call_id,
 *   result: {text} | {ids} | {cancelled: true}}`; `client_prompt_ended {call_id, reason}` closes it
 *   when another app answered or it timed out.
 * - Rex sends questions only to connections whose hello asked for API revision 17 or later: at
 *   revision 7, the phone's own, the action is told `unsupported`. So an action runs on a
 *   connection of its own at [API_REVISION], as an upload does at its revision, and the live one
 *   keeps its contract.
 */
object RexActions {
    /** The first API revision Rex sends `client_prompt_requested` to (seen on preview.22). */
    const val API_REVISION = 17

    /** How long `client.action` may take: its own 30 s, plus as long as a question may wait (an hour at most). */
    const val RUN_TIMEOUT_MS = 62L * 60 * 1000

    /** The configuration file's actions in [keymap] (a `client.keymap` answer) that the palette can run: none that need arguments. */
    fun parse(keymap: JSONObject): List<RexAction> {
        val arr = keymap.optJSONArray("actions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("kind") != "lua") return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val required = o.optJSONObject("args")?.optJSONArray("required")?.length() ?: 0
            RexAction(
                name = name,
                title = o.optString("title").ifBlank { name },
                category = o.optString("category").ifBlank { "Actions" },
                description = o.optString("description").ifBlank { null },
                keywords = o.optString("keywords").ifBlank { null },
                needsArgs = required > 0,
            )
        }
    }

    /** The question in a `client_prompt_requested` event's payload, or null when it is not one. */
    fun prompt(payload: JSONObject, answer: suspend (JSONObject) -> Unit): RexPrompt? {
        val callId = payload.optString("call_id").takeIf { it.isNotEmpty() } ?: return null
        val p = payload.optJSONObject("prompt") ?: return null
        val items = p.optJSONArray("items")
        return RexPrompt(
            callId = callId,
            action = payload.optString("action").ifBlank { null },
            kind = p.optString("kind"),
            title = p.optString("title"),
            placeholder = p.optString("placeholder").ifBlank { null },
            value = p.optString("value"),
            confirm = p.optString("confirm").ifBlank { null },
            multiple = p.optBoolean("multiple"),
            allowOther = p.optBoolean("allow_other"),
            items = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                val o = items!!.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RexPrompt.Item(
                    id = id,
                    title = o.optString("title").ifBlank { id },
                    subtitle = o.optString("subtitle").ifBlank { null },
                    detail = o.optString("detail").ifBlank { null },
                    selected = o.optBoolean("selected"),
                )
            },
            timeoutMs = payload.optLong("timeout_ms", 300_000),
            answer = answer,
        )
    }

    /** What the phone sends for typed text, chosen items, or a closed question. */
    fun text(text: String): JSONObject = JSONObject().put("text", text)
    fun ids(ids: List<String>): JSONObject = JSONObject().put("ids", JSONArray(ids))
    fun cancelled(): JSONObject = JSONObject().put("cancelled", true)

    /**
     * Runs [name] with [client], a connection of its own that is not yet open, at [endpoint]: in
     * [sessionId] and on [blockId] when given (the pane on screen), as Rex's palette does. The flow
     * says each question as it comes and when it ends elsewhere, then the outcome, and closes the
     * connection.
     */
    fun run(client: RexClient, endpoint: Endpoint, name: String, sessionId: String?, blockId: String?): Flow<ActionEvent> = channelFlow {
        var following: kotlinx.coroutines.Job? = null
        try {
            client.connect(endpoint)
            // An action given a session must come from a connection that may see it.
            if (sessionId != null) client.call("session.bootstrap", JSONObject().put("preferred_session_id", sessionId).put("event_scope", "session"))
            following = launch { follow(client) }
            val body = JSONObject().put("action", name).put("origin", "palette")
            if (sessionId != null) body.put("session_id", sessionId)
            if (sessionId != null && blockId != null) body.put("block_id", blockId)
            val answer = client.call("client.action", body, timeoutMs = RUN_TIMEOUT_MS)
            send(ActionEvent.Done(answer.opt("result")?.takeIf { it != JSONObject.NULL }?.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ActionEvent.Failed(e.message ?: "failed"))
        } finally {
            following?.cancel()
            client.close()
        }
    }

    private suspend fun ProducerScope<ActionEvent>.follow(client: RexClient) {
        client.events.collect { event ->
            val payload = event.optJSONObject("payload") ?: JSONObject()
            when (event.optString("type")) {
                "client_prompt_requested" -> {
                    val prompt = prompt(payload) { result ->
                        client.call("client.reply", JSONObject().put("call_id", payload.optString("call_id")).put("result", result))
                    }
                    if (prompt == null || (prompt.kind != "input" && prompt.kind != "pick")) {
                        // A kind this phone does not know: only this app steps out of the question.
                        client.send(
                            "client.reply",
                            JSONObject().put("call_id", payload.optString("call_id"))
                                .put("error", JSONObject().put("code", "not_supported").put("message", "RexPocket cannot show this question")),
                        )
                    } else {
                        send(ActionEvent.Prompt(prompt))
                    }
                }
                "client_prompt_ended" -> send(ActionEvent.PromptEnded(payload.optString("call_id"), payload.optString("reason")))
                // A call on another server: the phone has none to make it on for the action.
                "client_call_requested" -> client.send(
                    "client.reply",
                    JSONObject().put("call_id", payload.optString("call_id"))
                        .put("error", JSONObject().put("code", "not_supported").put("message", "RexPocket does not call other servers for an action")),
                )
            }
        }
    }
}

/** One of the configuration file's actions, as the palette lists it. */
data class RexAction(
    val name: String,
    val title: String,
    /** The palette's group: the file's `category`, "Actions" when it gives none. */
    val category: String = "Actions",
    val description: String? = null,
    val keywords: String? = null,
    /** It has required arguments, which the palette has no way to ask for: left out of it. */
    val needsArgs: Boolean = false,
)

/** A question an action asks (`rex.ui.input` or `rex.ui.pick`), and how to answer it. */
class RexPrompt(
    val callId: String,
    /** The name of the action asking, to say what the question is for. */
    val action: String?,
    /** `input` or `pick`. */
    val kind: String,
    val title: String,
    val placeholder: String?,
    /** What the field starts with. */
    val value: String,
    /** The submit button's label; the phone's own word when null. */
    val confirm: String?,
    val multiple: Boolean,
    val allowOther: Boolean,
    val items: List<Item>,
    val timeoutMs: Long,
    /** Sends the answer: [RexActions.text], [RexActions.ids] or [RexActions.cancelled]. */
    val answer: suspend (JSONObject) -> Unit,
) {
    data class Item(val id: String, val title: String, val subtitle: String? = null, val detail: String? = null, val selected: Boolean = false)

    override fun toString(): String = "RexPrompt($callId, $kind, ${items.size} items)"
}

/** What running an action says, in order: its questions, then how it ended. */
sealed interface ActionEvent {
    data class Prompt(val prompt: RexPrompt) : ActionEvent

    /** The question was answered on another device, or timed out: close it. */
    data class PromptEnded(val callId: String, val reason: String) : ActionEvent

    /** It ran; [result] is what it returned, as JSON, when anything. */
    data class Done(val result: String?) : ActionEvent
    data class Failed(val message: String) : ActionEvent
}
