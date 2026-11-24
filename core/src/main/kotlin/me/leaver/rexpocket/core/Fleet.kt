package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Several Rex hosts connected at once: one PocketSession per host, each with its own connection,
// backoff and alert ledger. See docs/design/multi-host.md.

/** A pane's bell or desktop notification, with the host it came from. */
data class HostSignal(val hostId: String, val signal: PaneSignal)

/** What [Fleet.alertMoves] decided for one host's new state. */
data class HostAlerts(val hostId: String, val state: PocketState, val moves: List<AlertMove>)

/**
 * The connected hosts. Each member is a [PocketSession] the fleet started and keeps until it is
 * removed; nothing one member does - a reconnect, a refusal, its backoff - reaches another. What
 * the app is doing (on screen, watching for alerts) applies to every member, and to one added later.
 */
class Fleet(private val scope: CoroutineScope) {
    /** One connected host. Its [planner] remembers which of its panes' news was already told. */
    class Member internal constructor(val hostId: String, val session: PocketSession, val job: Job) {
        val planner = AlertPlanner()
        internal var forward: Job? = null
    }

    private val _members = MutableStateFlow<Map<String, Member>>(emptyMap())

    /** The members by host id, in the order they were added. */
    val members: StateFlow<Map<String, Member>> = _members

    private val _signals = MutableSharedFlow<HostSignal>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every member's bells and desktop notifications, each with its host. */
    val signals: SharedFlow<HostSignal> = _signals

    private var active = true
    private var watching = false

    operator fun get(hostId: String): PocketSession? = _members.value[hostId]?.session

    fun member(hostId: String): Member? = _members.value[hostId]

    /**
     * Starts [session] as [hostId]'s connection, on [preferredSessionId] when Rex still has it. A
     * member that host already had is removed first and returned, for the caller to stop.
     */
    fun add(hostId: String, session: PocketSession, preferredSessionId: String? = null): Member {
        session.setActive(active)
        session.setWatching(watching)
        val job = session.start(scope, preferredSessionId)
        val member = Member(hostId, session, job)
        member.forward = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.signals.collect { _signals.emit(HostSignal(hostId, it)) }
        }
        var replaced: Member? = null
        _members.update { current ->
            replaced = current[hostId]
            LinkedHashMap(current).apply { remove(hostId); put(hostId, member) }
        }
        replaced?.forward?.cancel()
        replaced?.let { old -> scope.launch { old.session.stop() } }
        return member
    }

    /** Takes [hostId] out of the fleet; the caller stops its session (see [disconnect]). */
    fun remove(hostId: String): PocketSession? {
        var gone: Member? = null
        _members.update { current ->
            gone = current[hostId]
            if (gone == null) current else LinkedHashMap(current).apply { remove(hostId) }
        }
        gone?.forward?.cancel()
        return gone?.session
    }

    /** Removes [hostId] and closes its connection; every other member stays as it is. */
    suspend fun disconnect(hostId: String) {
        remove(hostId)?.stop()
    }

    /** The app is on screen or not: every member pauses or resumes its polls. */
    fun setActive(visible: Boolean) {
        active = visible
        _members.value.values.forEach { it.session.setActive(visible) }
    }

    /** Background alerts on or off: every member keeps its panes' status current while away. */
    fun setWatching(on: Boolean) {
        watching = on
        _members.value.values.forEach { it.session.setWatching(on) }
    }

    /** The phone's network changed: every member redials at once. */
    fun reconnectAll() {
        _members.value.values.forEach { it.session.reconnectNow() }
    }

    /** Each member's state, by host id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val states: Flow<Map<String, PocketState>> = _members.flatMapLatest { members ->
        if (members.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val ids = members.keys.toList()
            combine(members.values.map { it.session.state }) { states -> ids.zip(states).toMap() }
        }
    }

    /**
     * Every member's status changes turned into alert moves by its own planner: a pane of one host
     * never settles or repeats another's. [away] is asked at each change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun alertMoves(prefs: Flow<AlertPrefs>, away: () -> Boolean): Flow<HostAlerts> =
        alertMovesBy(prefs.map { p -> { _: String -> p } }, away)

    /** As [alertMoves], with each host's own choices: [prefsOf] gives them by host id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun alertMovesBy(prefsOf: Flow<(String) -> AlertPrefs>, away: () -> Boolean): Flow<HostAlerts> = _members.flatMapLatest { members ->
        merge(
            *members.values.map { member ->
                combine(member.session.state, prefsOf) { st, of ->
                    HostAlerts(member.hostId, st, member.planner.status(st.blockStatus, away(), of(member.hostId)))
                }
            }.toTypedArray(),
        )
    }

    /** Types [text] and Enter into [blockId] of [hostId]'s [sessionId], once that host is live. */
    suspend fun reply(hostId: String, sessionId: String, blockId: String, text: String, waitMs: Long = LIVE_WAIT_MS, enter: Boolean = true): Boolean =
        onLive(hostId, waitMs) { it.replyTo(sessionId, blockId, text, enter) } ?: false

    /** Approve or Deny the prompt [stamp] names in [blockId] of [hostId]'s [sessionId], once that host is live. */
    suspend fun answerPrompt(hostId: String, sessionId: String, blockId: String, stamp: String, approve: Boolean, waitMs: Long = LIVE_WAIT_MS): PromptAnswer =
        onLive(hostId, waitMs) { it.answerPrompt(sessionId, blockId, stamp, approve) } ?: PromptAnswer.NotSent

    /** Ctrl+C in [blockId] of [hostId]'s [sessionId], once that host is live. */
    suspend fun interrupt(hostId: String, sessionId: String, blockId: String, waitMs: Long = LIVE_WAIT_MS): Boolean =
        onLive(hostId, waitMs) { it.interrupt(sessionId, blockId) } ?: false

    /** [send] on [hostId]'s session once it is live; null when there is none, or it did not come up in [waitMs]. */
    private suspend fun <T> onLive(hostId: String, waitMs: Long, send: suspend (PocketSession) -> T): T? {
        val session = this[hostId] ?: return null
        // Android may have cut the connection while the app was away; it redials, so give it a
        // moment, and skip whatever wait its backoff was in.
        session.retryNow()
        withTimeoutOrNull(waitMs) { session.state.first { it.phase == Phase.Live } } ?: return null
        return send(session)
    }

    private companion object {
        const val LIVE_WAIT_MS = 6_000L
    }
}

