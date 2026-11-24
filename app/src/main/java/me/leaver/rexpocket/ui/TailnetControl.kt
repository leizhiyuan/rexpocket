package me.leaver.rexpocket.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.core.NodeWatchdog
import me.leaver.rexpocket.core.TailnetPlan
import me.leaver.rexpocket.core.TailnetPolicies
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetSituation
import me.leaver.rexpocket.core.nodeTrouble
import me.leaver.rexpocket.core.planTailnet
import me.leaver.rexpocket.core.transientStartFailure
import me.leaver.rexpocket.core.watchdogBackoffMs
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
    /** The account the node runs, whose choices apply: each account is a tailnet of its own. */
    val policyId: String = id,
)

/** What the app is doing with one tailnet's node. */
data class TailnetRun(
    /** Started, or starting. */
    val running: Boolean = false,
    /** Why the last start failed; cleared by the next start. */
    val error: String? = null,
    /** While it is kept up only for its linger: when it stops. */
    val lingerUntil: Long? = null,
    /** How many times the watchdog (or a network change, or Restart) started the node again. */
    val restarts: Int = 0,
    /** Why it was last started again, for the diagnostics. */
    val lastRestart: String? = null,
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
    /**
     * Waits, for a moment at most, for a network Android has validated, before a watched node
     * starts: a start on a network that is still coming (the app back in the foreground, its
     * network unblocked a moment later) fails its first dial and has to wait for a retry.
     */
    private val networkReady: suspend () -> Unit = {},
    /** How long a watched node may stay in trouble before the watchdog restarts it. */
    private val stuckAfterMs: Long = NodeWatchdog.STUCK_AFTER_MS,
    /** How often the watchdog looks again at a node in trouble. */
    private val watchTickMs: Long = WATCH_TICK_MS,
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

    // The watchdog of each watched node (TailnetNode.watched), its job while the node runs, and the
    // retry of a start that failed on the network. See docs/design/tailnet-control.md, "Watchdog".
    private val watchdogs = java.util.concurrent.ConcurrentHashMap<String, NodeWatchdog>()
    private val watchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val retryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val retries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Each tailnet's last failed start, numbered: a start that waited behind it takes its answer. */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Exception>>()
    private val failureCount = java.util.concurrent.atomic.AtomicLong()

    /** Counts the network changes: a start that failed across one is tried again at once. */
    private val networkChanges = java.util.concurrent.atomic.AtomicLong()

    private fun watchdog(id: String) = watchdogs.getOrPut(id) { NodeWatchdog(stuckAfterMs) }

    init {
        scope.launch { merge(*(changes + policies).toTypedArray()).collect { evaluate() } }
    }

    fun run(id: String): TailnetRun = _runs.value[id] ?: TailnetRun()

    /** The id [id]'s choices are kept under: the account its node runs. */
    private fun pid(id: String): String = spec(id)?.policyId ?: id

    /** Tailnet [id]'s choices: its running account's. */
    fun policyOf(id: String): TailnetPolicy = policies.value.of(pid(id))

    /** Whether tailnet [id] (its running account) was disconnected by hand. */
    fun isOff(id: String): Boolean = policies.value.isOff(pid(id))

    /**
     * Starts [id]'s node again on what it runs now (another account): stopped, then up as its
     * plan says. The node's lock keeps the stop before the start.
     */
    fun restart(id: String) {
        val spec = spec(id) ?: return
        if (run(id).running) stop(spec, "account switched")
        update(id) { it.copy(error = null) }
        evaluate()
    }

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
            policyOf(id),
            TailnetSituation(
                joined = spec.joined,
                off = isOff(id),
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
        val failedBefore = failureCount.get()
        val network = networkChanges.get()
        var inherited = false
        return try {
            withContext(Dispatchers.IO) {
                lock(id).withLock {
                    // Two asks at once (a connection, and the plan) start the node once; a new key starts it again.
                    val config = spec.config(setupKey)
                    // A start that failed while this one waited is its answer too: queued asks do not
                    // each wait out the same dead dial again, one after the other.
                    failures[id]?.takeIf { (n, _) -> setupKey.isEmpty() && n > failedBefore }?.let {
                        inherited = true
                        throw it.second
                    }
                    val reused = ports[id]?.takeIf { (was, _) -> setupKey.isEmpty() && was.sameNode(config) }?.second
                    try {
                        when {
                            reused == null -> startLocked(spec, config)
                            // A node known to be stuck is not handed to a connect as it is: it starts again.
                            stuckNow(spec) -> restartLocked(spec, config, "reused while ${troubleOf(spec)}")
                            else -> reused
                        }
                    } catch (e: Exception) {
                        // Noted before the lock goes, so the next ask in the queue sees it.
                        if (e !is CancellationException) failures[id] = failureCount.incrementAndGet() to e
                        throw e
                    }
                }
            }
                .also {
                    update(id) { it.copy(running = true, error = null) }
                    retries.remove(id)
                    watch(spec)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The failure of the start this one waited behind was said, and its retry planned, already.
            if (inherited) update(id) { it.copy(running = false, error = e.message.orEmpty()) }
            else failedStart(spec, e, acrossNetworkChange = networkChanges.get() != network)
            throw e
        } finally {
            // A start asked for while the plan was down (a Test connection) still ends with the plan.
            scope.launch { evaluate() }
        }
    }

    /** Starts [spec]'s node for [config], under its lock: a watched one once the network is ready. */
    private suspend fun startLocked(spec: TailnetSpec, config: TailnetConfig): Int {
        if (spec.node.watched) networkReady()
        return spec.node.start(config).also { ports[spec.id] = config to it }
    }

    /** Stops [spec]'s node and starts it again, under its lock: the watchdog's cure, or Restart. */
    private suspend fun restartLocked(spec: TailnetSpec, config: TailnetConfig, why: String): Int {
        Diagnostics.event(CONN_TAG, "tailnet ${spec.id}: restarting the node ($why)")
        update(spec.id) { it.copy(restarts = it.restarts + 1, lastRestart = why) }
        val dog = watchdog(spec.id)
        synchronized(dog) { dog.restarted(clock()) }
        ports.remove(spec.id)
        runCatching { spec.node.stop() }
        return startLocked(spec, config)
    }

    /** What is wrong with [spec]'s node now, or null. */
    private fun troubleOf(spec: TailnetSpec): String? = spec.node.status.value.let { nodeTrouble(it.backendState, it.health) }

    /** Whether [spec]'s node is watched and has been in trouble for the whole bound. */
    private fun stuckNow(spec: TailnetSpec): Boolean {
        if (!spec.node.watched) return false
        val dog = watchdog(spec.id)
        val now = clock()
        return synchronized(dog) {
            dog.observe(troubleOf(spec), needed = false, now = now)
            dog.stuck(now)
        }
    }

    /** A start of [spec] failed: said, and tried again by itself when the network was to blame. */
    private fun failedStart(spec: TailnetSpec, e: Exception, acrossNetworkChange: Boolean) {
        val id = spec.id
        Diagnostics.event(CONN_TAG, "tailnet $id: start failed")
        update(id) { it.copy(running = false, error = e.message.orEmpty()) }
        if (!spec.node.watched || !transientStartFailure(e.message)) return
        val attempt = (retries[id] ?: 0) + 1
        retries[id] = attempt
        val wait = if (acrossNetworkChange) 0L else watchdogBackoffMs(attempt)
        Diagnostics.event(CONN_TAG, "tailnet $id: the network did not answer; starting it again in ${wait / 1000}s (try $attempt)")
        retryJobs.remove(id)?.cancel()
        retryJobs[id] = scope.launch {
            delay(wait)
            if (!run(id).running && run(id).error != null && plan(id) == TailnetPlan.Up) retry(id)
        }
    }

    /**
     * Watches [spec]'s node while it runs, when it is a watched one: in trouble for longer than the
     * bound while something needs it, it is started again, with a backoff between restarts.
     */
    private fun watch(spec: TailnetSpec) {
        if (!spec.node.watched || watchJobs[spec.id]?.isActive == true) return
        val id = spec.id
        watchJobs[id] = scope.launch {
            // A node just (re)started has said nothing yet ("" here): that is not news either way.
            spec.node.status.map { if (it.backendState.isBlank()) "" else nodeTrouble(it.backendState, it.health) }
                .distinctUntilChanged()
                .collectLatest { trouble ->
                    if (trouble == "") return@collectLatest
                    while (run(id).running) {
                        val dog = watchdog(id)
                        val needed = plan(id) != TailnetPlan.Down
                        val now = clock()
                        if (synchronized(dog) { dog.observe(trouble, needed, now) }) restartNode(id, trouble.orEmpty())
                        if (trouble == null) return@collectLatest
                        delay(watchTickMs)
                    }
                }
        }
    }

    /**
     * Stops [id]'s node and starts it again: the watchdog's restart, a network change on a node in
     * trouble, or Restart by hand ([why] says which). One that is not running is just started.
     */
    fun restartNode(id: String, why: String) {
        val spec = spec(id) ?: return
        if (!run(id).running) {
            Diagnostics.event(CONN_TAG, "tailnet $id: starting ($why)")
            retry(id)
            return
        }
        val network = networkChanges.get()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    lock(id).withLock {
                        try {
                            restartLocked(spec, spec.config(""), why)
                        } catch (e: Exception) {
                            if (e !is CancellationException) failures[id] = failureCount.incrementAndGet() to e
                            throw e
                        }
                    }
                }
                update(id) { it.copy(running = true, error = null) }
                watch(spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedStart(spec, e, acrossNetworkChange = networkChanges.get() != network)
            }
        }
    }

    /**
     * The phone's network changed, or became validated: a watched node in trouble starts again at
     * once, and one whose start failed on the network is tried again; a healthy node is left alone,
     * since a restart drops its connections.
     */
    fun networkChanged() {
        networkChanges.incrementAndGet()
        for (spec in specs()) {
            if (!spec.node.watched) continue
            val id = spec.id
            val r = run(id)
            val trouble = troubleOf(spec)
            when {
                r.running && trouble != null && ports.containsKey(id) && plan(id) != TailnetPlan.Down ->
                    restartNode(id, "network changed while $trouble")
                !r.running && r.error != null && transientStartFailure(r.error) && plan(id) == TailnetPlan.Up -> {
                    Diagnostics.event(CONN_TAG, "tailnet $id: network changed; starting it again")
                    retryJobs.remove(id)?.cancel()
                    retry(id)
                }
            }
        }
    }

    private fun stop(spec: TailnetSpec, why: String) {
        Diagnostics.event(CONN_TAG, "tailnet ${spec.id}: stopping ($why)")
        update(spec.id) { it.copy(running = false, lingerUntil = null) }
        watchJobs.remove(spec.id)?.cancel()
        retryJobs.remove(spec.id)?.cancel()
        retries.remove(spec.id)
        watchdogs.remove(spec.id)
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
        if (isOff(id)) setPolicies(policies.value.switched(pid(id), on = true))
    }

    /** Disconnect by hand: down until connected again, by hand or by a host on it. */
    fun disconnect(id: String) {
        Diagnostics.event(CONN_TAG, "tailnet $id: disconnect")
        setPolicies(policies.value.switched(pid(id), on = false))
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
        setPolicies(policies.value.with(pid(id), next))
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
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        lingerJobs.values.forEach { it.cancel() }
        lingerJobs.clear()
        for (spec in specs()) if (run(spec.id).running) stop(spec, "everything stops")
        // A node another build or backend left running is stopped too.
        _runs.value = _runs.value.mapValues { it.value.copy(running = false, lingerUntil = null) }
    }

    private companion object {
        /** How often the watchdog looks again at a node in trouble. */
        const val WATCH_TICK_MS = 5_000L
    }
}
