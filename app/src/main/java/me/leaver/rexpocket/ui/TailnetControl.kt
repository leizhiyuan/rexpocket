package me.leaver.rexpocket.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.core.TailnetPlan
import me.leaver.rexpocket.core.TailnetPolicies
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetSituation
import me.leaver.rexpocket.core.planTailnet
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetNode

// When each tailnet's node runs: by hand, while the app is open or only when a host needs it, in
// the background, after a boot. The choices are core's (TailnetPlan); this acts on them, and is the
// only place a node is started or stopped. See docs/design/tailnet-control.md.

/** One tailnet the app can run: its node, whether it is joined, and what it starts with. */
class TailnetSpec(
    val id: String,
    val name: String,
    val node: TailnetNode,
    val joined: Boolean,
    val config: (setupKey: String) -> TailnetConfig,
)

/** What the app is doing with one tailnet's node. */
data class TailnetRun(
    /** Started, or starting. */
    val running: Boolean = false,
    /** Why the last start failed; cleared by the next start. */
    val error: String? = null,
    /** While it is kept up only for its linger: when it stops. */
    val lingerUntil: Long? = null,
)

class TailnetControl(
    private val scope: CoroutineScope,
    /** Every tailnet the app can run, as the settings are now. */
    private val specs: () -> List<TailnetSpec>,
    /** How many connected hosts use each tailnet now, by its id. */
    private val users: () -> Map<String, Int>,
    /** Background mode on and the app away long enough to let things go, or started without a screen. */
    private val away: () -> Boolean,
    /** Changes whenever one of the three above may give something else: the plans are looked at again. */
    private val changes: List<Flow<*>>,
    private val read: () -> TailnetPolicies,
    private val write: (TailnetPolicies) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val policies = MutableStateFlow(read())

    private val _runs = MutableStateFlow<Map<String, TailnetRun>>(emptyMap())
    val runs: StateFlow<Map<String, TailnetRun>> = _runs

    /** Whether the plans are acted on: from the first screen or restart, until everything stops. */
    private var acting = false

    private val idleSince = HashMap<String, Long>()
    private val lingerJobs = HashMap<String, Job>()
    private val locks = HashMap<String, Mutex>()

    /** The port each node gave since it last stopped, under its lock. */
    private val ports = java.util.concurrent.ConcurrentHashMap<String, Pair<TailnetConfig, Int>>()
    private var before: Map<String, Int> = emptyMap()

    init {
        scope.launch { merge(*(changes + policies).toTypedArray()).collect { evaluate() } }
    }

    fun run(id: String): TailnetRun = _runs.value[id] ?: TailnetRun()

    private fun spec(id: String): TailnetSpec? = specs().firstOrNull { it.id == id }

    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    private fun update(id: String, change: (TailnetRun) -> TailnetRun) {
        _runs.value = _runs.value + (id to change(run(id)))
    }

    /** Acts on the plans from now: the screen's first start, or Android starting the app without one. */
    fun begin() {
        if (acting) return
        acting = true
        evaluate()
    }

    /** What each tailnet's plan is now. */
    fun plan(id: String): TailnetPlan {
        val spec = spec(id) ?: return TailnetPlan.Down
        val now = clock()
        return planTailnet(
            policies.value.of(id),
            TailnetSituation(
                joined = spec.joined,
                off = policies.value.isOff(id),
                users = users()[id] ?: 0,
                away = away(),
                running = run(id).running,
                idleSince = idleSince[id],
                now = now,
            ),
        )
    }

    /** How many tailnets want to be up now: a restart without a screen keeps the watch for them. */
    fun wantedUp(): Int = specs().count { plan(it.id) is TailnetPlan.Up }

    /** Looks at every tailnet's plan and starts, keeps or stops its node. */
    fun evaluate() {
        // The moment the last host of a tailnet went is when its linger starts.
        val now = users()
        for ((id, n) in before) if (n > 0 && (now[id] ?: 0) == 0) idleSince[id] = clock()
        before = now
        if (!acting) return
        for (spec in specs()) {
            val id = spec.id
            val plan = plan(id)
            lingerJobs.remove(id)?.cancel()
            when (plan) {
                TailnetPlan.Up -> {
                    update(id) { it.copy(lingerUntil = null) }
                    if (!run(id).running && run(id).error == null) scope.launch { runCatching { start(id) } }
                }
                is TailnetPlan.Linger -> {
                    update(id) { it.copy(lingerUntil = plan.until) }
                    lingerJobs[id] = scope.launch {
                        delay((plan.until - clock()).coerceAtLeast(0))
                        evaluate()
                    }
                }
                TailnetPlan.Down -> if (run(id).running || run(id).lingerUntil != null) stop(spec, "plan")
            }
        }
    }

    /**
     * Starts [id]'s node, or waits for the one starting, and gives its SOCKS port. A connection on
     * the tailnet and the host dialog's Test connection call this; it counts as a use, so a tailnet
     * on only when needed lingers from now. Throws with the node's words when it cannot start.
     */
    suspend fun start(id: String, setupKey: String = ""): Int {
        val spec = spec(id) ?: throw me.leaver.rexpocket.net.TailnetException("no such tailnet")
        idleSince[id] = clock()
        update(id) { it.copy(running = true, error = null) }
        return try {
            withContext(Dispatchers.IO) {
                lock(id).withLock {
                    // Two asks at once (a connection, and the plan) start the node once; a new key starts it again.
                    val config = spec.config(setupKey)
                    ports[id]?.takeIf { (was, _) -> setupKey.isEmpty() && was.sameNode(config) }?.second
                        ?: spec.node.start(config).also { ports[id] = config to it }
                }
            }
                .also { update(id) { it.copy(running = true, error = null) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.event(CONN_TAG, "tailnet $id: start failed")
            update(id) { it.copy(running = false, error = e.message.orEmpty()) }
            throw e
        } finally {
            // A start asked for while the plan was down (a Test connection) still ends with the plan.
            scope.launch { evaluate() }
        }
    }

    private fun stop(spec: TailnetSpec, why: String) {
        Diagnostics.event(CONN_TAG, "tailnet ${spec.id}: stopping ($why)")
        update(spec.id) { it.copy(running = false, lingerUntil = null) }
        scope.launch(Dispatchers.IO) {
            lock(spec.id).withLock {
                ports.remove(spec.id)
                runCatching { spec.node.stop() }
            }
        }
    }

    /** Connect by hand: on again, and up now; one only needed by hosts lingers from now. */
    fun connect(id: String) {
        Diagnostics.event(CONN_TAG, "tailnet $id: connect")
        switchOn(id)
        update(id) { it.copy(error = null) }
        begin()
        scope.launch { runCatching { start(id) } }
    }

    /** Clears a Disconnect by hand, as a host connecting on the tailnet does. */
    fun switchOn(id: String) {
        if (policies.value.isOff(id)) setPolicies(policies.value.switched(id, on = true))
    }

    /** Disconnect by hand: down until connected again, by hand or by a host on it. */
    fun disconnect(id: String) {
        Diagnostics.event(CONN_TAG, "tailnet $id: disconnect")
        setPolicies(policies.value.switched(id, on = false))
        update(id) { it.copy(error = null) }
        spec(id)?.let { if (run(id).running) stop(it, "by hand") }
    }

    /** Starts it again after its start failed. */
    fun retry(id: String) {
        update(id) { it.copy(error = null) }
        evaluate()
        if (!run(id).running) scope.launch { runCatching { start(id) } }
    }

    fun setPolicy(id: String, next: TailnetPolicy) {
        setPolicies(policies.value.with(id, next))
    }

    private fun setPolicies(next: TailnetPolicies) {
        write(next)
        policies.value = next
    }

    /**
     * Everything stops: the screen closed with background mode off, or the watch's Stop with no
     * screen. Nothing starts again until the next [begin].
     */
    fun stopAll() {
        acting = false
        lingerJobs.values.forEach { it.cancel() }
        lingerJobs.clear()
        for (spec in specs()) if (run(spec.id).running) stop(spec, "everything stops")
        // A node another build or backend left running is stopped too.
        _runs.value = _runs.value.mapValues { it.value.copy(running = false, lingerUntil = null) }
    }
}
