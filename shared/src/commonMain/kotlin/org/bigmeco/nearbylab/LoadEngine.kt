package org.bigmeco.nearbylab

import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Main-thread state machine: no clock synchronization or automatic pairing approval. */
internal class LoadEngine(
    private val now: () -> Long,
    private val peers: () -> List<String>,
    private val isHost: () -> Boolean,
    send: (String, String) -> Unit,
    private val log: (String, String) -> Unit,
    private val changeRole: (Boolean) -> Unit,
) {
    val nodeId = Random.nextLong().toULong().toString(16).padStart(16, '0')
    private val wire = LoadWire(now, send, ::incoming) { log("load_error", it) }

    private data class Pending(val peer: String, val sentAt: Long, val bytes: Int)

    private class Counts {
        var sent = 0L
        var ok = 0L
        var timeout = 0L
        var failed = 0L
        val latency = Histogram()
    }

    private class Run(
        val id: String,
        val plan: LoadPlan,
        val targets: List<String>,
        val start: Long,
        val tx: Long,
        val rx: Long,
        val duplicatesAtStart: Long,
        val orderAtStart: Long,
        val corruptAtStart: Long,
    ) {
        val pending = mutableMapOf<Pair<String, Long>, Pending>()
        val counts = targets.associateWith { Counts() }
        val histogram = Histogram()
        var seq = 0L
        var sent = 0L
        var ok = 0L
        var timeout = 0L
        var failed = 0L
        var bytes = 0L
        var peak = 0
        var peakBytes = 0
        var limited = 0L
        var stopAt: Long? = null
        var nextDue = start.toDouble()
        var reportTo: String? = null
        var reportToken = ""
    }

    private data class Fan(
        val origin: String,
        val run: String,
        val seq: Long,
        val awaiting: MutableSet<String>,
        val snapshot: GameSnapshot,
        val start: Long,
    )

    private var run: Run? = null
    private var batch = ArrayDeque<LoadPlan>()
    private var results = listOf<LoadResult>()
    private var remoteResults = listOf<LoadResult>()
    private var expectedRemote = mutableSetOf<String>()
    private var remoteDeadline = 0L
    private var remoteToken = ""
    private var txSequence = 0L
    private var received = 0L
    private var duplicates = 0L
    private var outOfOrder = 0L
    private val identities = mutableMapOf<String, String>()
    private val seen = linkedSetOf<String>()
    private val lastSeq = mutableMapOf<String, Long>()
    private val fans = mutableMapOf<String, Fan>()
    private var replica = GameSnapshot()
    private var members = setOf(nodeId)
    private var migration = ""
    private var migrationStart = 0L
    private var migrationFinished = 0L
    private var migrationTarget = ""
    private var migrationId = ""
    private var migrationAwait = mutableSetOf<String>()
    private var migrationExpected = setOf<String>()
    private var migrationPhase = ""
    private var roleDue: Long? = null
    private var roleHost = false
    private var lastProgress = 0L
    private var preparedSnapshot: GameSnapshot? = null
    private var lastTelemetry = 0L
    private var lastTraffic = -1L
    private var lastReplicaVersion: Pair<Long, Long>? = null

    fun state(): LoadState {
        val r = run
        return LoadState(
            r != null,
            r != null && (r.stopAt != null || now() - r.start >= r.plan.seconds * 1000L),
            r?.id ?: "",
            r?.plan ?: LoadPlan(),
            r?.sent ?: 0,
            r?.ok ?: 0,
            r?.pending?.size ?: 0,
            wire.queuedBytes,
            r?.let { now() - it.start } ?: 0,
            wire.txBytes,
            wire.rxBytes,
            received,
            wire.corrupt,
            duplicates + wire.duplicateChunks,
            results,
            remoteResults,
            replica.copy(data = ""),
            replica.digest,
            nodeId,
            identities.toMap(),
            migration,
            if (migrationStart == 0L) 0
            else (if (migrationFinished > 0) migrationFinished else now()) - migrationStart,
            migrationExpected.size,
            (identities.values.toSet().intersect(migrationExpected) +
                    if (nodeId in migrationExpected) setOf(nodeId) else emptySet())
                .size,
            expectedRemote.size,
        )
    }

    fun start(plan: LoadPlan, suite: Boolean = false) {
        require(migrationPhase.isEmpty()) { "Migration active" }
        require(run == null && expectedRemote.isEmpty()) { "Load already active" }
        plan.validate()
        require(peers().isNotEmpty()) { "No connected peers" }
        require(plan.mode != LoadMode.RELAY || !isHost()) {
            "Relay starts on clients; use start-clients on host"
        }
        batch.clear()
        if (suite) {
            listOf(64, 1024, 8192, 16384, 65536, 262144, 1048576).forEach {
                batch.addLast(plan.copy(bytes = it, rate = 5, seconds = 5, window = 8))
            }
            listOf(10, 50, 200, 1000, 0).forEach {
                batch.addLast(plan.copy(bytes = 256, rate = it, seconds = 5, window = 64))
            }
            begin(batch.removeFirst())
        } else begin(plan)
    }

    private fun begin(plan: LoadPlan) {
        val targets =
            if (plan.mode == LoadMode.RELAY) listOf(peers().single()) else peers().toList()
        require(plan.window >= targets.size) { "Window must cover all recipients" }
        run =
            Run(
                "$nodeId-${++txSequence}",
                plan,
                targets,
                now(),
                wire.txBytes,
                wire.rxBytes,
                duplicates + wire.duplicateChunks,
                outOfOrder,
                wire.corrupt,
            )
        log(
            "load_start",
            reportJson.encodeToString(
                LoadPacket("start", run = run!!.id, node = nodeId, plan = plan),
            ),
        )
    }

    fun startClients(plan: LoadPlan) {
        plan.validate()
        require(isHost() && peers().isNotEmpty() && run == null && expectedRemote.isEmpty())
        require(migrationPhase.isEmpty())
        val id = "$nodeId-clients-${++txSequence}"
        remoteToken = id
        expectedRemote = peers().toMutableSet()
        remoteDeadline = now() + plan.seconds * 1000 + plan.timeoutMs + 15_000
        require(
            wire.enqueue(
                peers(),
                LoadPacket("start_clients", run = id, plan = plan.copy(mode = LoadMode.RELAY)),
            ),
        )
        log("load_clients_start", id)
    }

    fun cancel(reason: String = "cancelled") {
        batch.clear()
        if (run != null) {
            wire.cancelData(run!!.id)
            finish(reason, cancelled = true)
        }
        if (expectedRemote.isNotEmpty())
            wire.enqueue(
                expectedRemote.toList(),
                LoadPacket("cancel_clients", target = remoteToken),
            )
        expectedRemote.clear()
    }

    private fun finish(reason: String, cancelled: Boolean = false) {
        val r = run ?: return
        val result =
            LoadResult(
                r.id,
                r.plan,
                reason,
                maxOf(1, now() - r.start),
                r.sent,
                r.ok,
                r.timeout,
                r.failed,
                if (cancelled) r.pending.size.toLong() else 0,
                r.bytes,
                wire.txBytes - r.tx,
                wire.rxBytes - r.rx,
                r.peak,
                r.peakBytes,
                r.limited,
                duplicates + wire.duplicateChunks - r.duplicatesAtStart,
                outOfOrder - r.orderAtStart,
                wire.corrupt - r.corruptAtStart,
                r.histogram.value(),
                r.counts.map { (ep, c) ->
                    PeerLoadResult(ep, c.sent, c.ok, c.timeout, c.failed, c.latency.value())
                },
            )
        results = (results + result).takeLast(30)
        log("load_result", reportJson.encodeToString(result))
        r.reportTo?.let {
            wire.enqueue(
                listOf(it),
                LoadPacket("result", run = r.id, target = r.reportToken, result = result),
            )
        }
        run = null
        if (batch.isNotEmpty() && !cancelled) begin(batch.removeFirst())
    }

    private fun done(peer: String, p: LoadPacket, ok: Boolean) {
        val r = run ?: return
        if (p.run != r.id) return
        val sent =
            r.pending.remove(peer to p.seq)
                ?: run {
                    duplicates++
                    return
                }
        val c = r.counts.getValue(peer)
        if (ok) {
            val latency = now() - sent.sentAt
            r.ok++
            r.bytes += sent.bytes
            c.ok++
            r.histogram.add(latency)
            c.latency.add(latency)
        } else {
            r.failed++
            c.failed++
        }
    }

    private fun seenOnce(key: String): Boolean {
        if (!seen.add(key)) {
            duplicates++
            return false
        }
        while (seen.size > 8192) seen.remove(seen.first())
        return true
    }

    private fun observeSequence(peer: String, p: LoadPacket) {
        val key = "$peer/${p.run}"
        val last = lastSeq[key] ?: 0
        if (p.seq < last) outOfOrder++
        lastSeq[key] = maxOf(last, p.seq)
        while (lastSeq.size > 256) lastSeq.remove(lastSeq.keys.first())
    }

    fun connected(peer: String) {
        wire.enqueue(
            listOf(peer),
            LoadPacket(
                "hello",
                node = nodeId,
                snapshot = if (isHost()) replica else null,
                members = if (isHost()) (identities.values + nodeId).distinct() else emptyList(),
            ),
        )
    }

    fun disconnected(peer: String) {
        identities.remove(peer)
        run?.let { r ->
            if (peer in r.targets) {
                r.pending
                    .filterKeys { it.first == peer }
                    .keys
                    .toList()
                    .forEach {
                        r.pending.remove(it)
                        r.failed++
                        r.counts.getValue(peer).failed++
                    }
                batch.clear()
                finish("disconnected", cancelled = true)
            }
        }
        expectedRemote.remove(peer)
        fans.values
            .filter { peer in it.awaiting || peer == it.origin }
            .toList()
            .forEach {
                wire.enqueue(listOf(it.origin), LoadPacket("nack", run = it.run, seq = it.seq))
                fans.remove("${it.origin}/${it.run}/${it.seq}")
            }
        if (migrationPhase == "prepare" && peer in migrationAwait)
            abortMigration("peer_disconnected")
    }

    fun topologyStopped() {
        cancel("topology_stopped")
        wire.clear()
        identities.clear()
        fans.clear()
    }

    fun incomingWire(peer: String, raw: String, body: kotlinx.serialization.json.JsonElement) =
        wire.incoming(peer, raw, body)

    private fun incoming(peer: String, p: LoadPacket) {
        when (p.op) {
            "hello" -> {
                require(p.node.isNotEmpty() && p.node != nodeId)
                identities[peer] = p.node
                if (isHost()) {
                    members = identities.values.toSet() + nodeId
                    wire.enqueue(
                        peers(),
                        LoadPacket(
                            "roster",
                            node = nodeId,
                            members = members.toList(),
                            snapshot = replica,
                        ),
                    )
                } else {
                    p.snapshot?.let { apply(it) }
                    members = p.members.toSet() + nodeId + p.node
                }
                checkMigrationRecovered()
            }
            "roster" ->
                if (!isHost()) {
                    members = p.members.toSet()
                    p.snapshot?.let { apply(it) }
                    checkMigrationRecovered()
                }
            "probe" -> {
                received++
                observeSequence(peer, p)
                require(p.data.length == p.size && checksum(p.data) == p.digest) {
                    "Probe checksum"
                }
                seenOnce("$peer/${p.run}/${p.seq}")
                wire.enqueue(listOf(peer), LoadPacket("receipt", run = p.run, seq = p.seq))
            }
            "receipt",
            "committed" -> done(peer, p, true)
            "nack" -> done(peer, p, false)
            "command" -> handleCommand(peer, p)
            "state" -> {
                require(!isHost())
                val s = requireNotNull(p.snapshot)
                require(s.digest == p.digest) { "State checksum" }
                received++
                apply(s)
                wire.enqueue(
                    listOf(peer),
                    LoadPacket(
                        "applied",
                        run = p.run,
                        seq = p.seq,
                        target = p.target,
                        digest = s.digest,
                    ),
                )
            }
            "applied" ->
                if (isHost()) {
                    val key = p.target
                    val fan = fans[key] ?: return
                    require(p.digest == fan.snapshot.digest)
                    fan.awaiting.remove(peer)
                    if (fan.awaiting.isEmpty()) {
                        wire.enqueue(
                            listOf(fan.origin),
                            LoadPacket("committed", run = fan.run, seq = fan.seq),
                        )
                        fans.remove(key)
                    }
                }
            "start_clients" -> {
                require(!isHost())
                val plan = requireNotNull(p.plan)
                plan.validate()
                if (run != null || migrationPhase.isNotEmpty()) {
                    wire.enqueue(listOf(peer), LoadPacket("busy", target = p.run))
                    log("load_remote_busy", p.run)
                    return
                }
                start(plan)
                run!!.reportTo = peer
                run!!.reportToken = p.run
            }
            "cancel_clients" ->
                if (!isHost() && run?.reportToken == p.target) cancel("remote_cancel")
            "busy" ->
                if (isHost() && p.target == remoteToken) {
                    expectedRemote.remove(peer)
                    log("load_remote_busy", peer)
                }
            "result" -> {
                val result = requireNotNull(p.result)
                remoteResults = (remoteResults + result).takeLast(30)
                if (p.target == remoteToken) expectedRemote.remove(peer)
                log("load_remote_result", reportJson.encodeToString(result))
            }
            "prepare",
            "ready",
            "commit",
            "commit_ack",
            "abort" -> migrationPacket(peer, p)
            else -> log("load_unknown", p.op)
        }
    }

    private fun apply(s: GameSnapshot) {
        if (s.epoch > replica.epoch || (s.epoch == replica.epoch && s.revision >= replica.revision))
            replica = s
        else outOfOrder++
    }

    private fun handleCommand(peer: String, p: LoadPacket) {
        require(isHost())
        received++
        observeSequence(peer, p)
        require(p.data.length == p.size && checksum(p.data) == p.digest) { "Command checksum" }
        val key = "$peer/${p.run}/${p.seq}"
        if (key in fans) {
            duplicates++
            return
        }
        if (!seenOnce(key)) {
            wire.enqueue(listOf(peer), LoadPacket("nack", run = p.run, seq = p.seq))
            return
        }
        if (
            migrationPhase.isNotEmpty() ||
                fans.size >= 128 ||
                fans.values.sumOf { it.snapshot.data.length.toLong() } + p.size > LoadWire.BUDGET ||
                !wire.canQueue((p.size + 1000L) * peers().size)
        ) {
            wire.enqueue(listOf(peer), LoadPacket("nack", run = p.run, seq = p.seq))
            return
        }
        val s =
            replica.copy(
                revision = replica.revision + 1,
                counter = replica.counter + 1,
                data = p.data,
            )
        val targets = peers().toList()
        if (
            !wire.enqueue(
                targets,
                LoadPacket(
                    "state",
                    run = p.run,
                    seq = p.seq,
                    snapshot = s,
                    digest = s.digest,
                    target = key,
                ),
            )
        ) {
            wire.enqueue(listOf(peer), LoadPacket("nack", run = p.run, seq = p.seq))
            return
        }
        replica = s
        fans[key] = Fan(peer, p.run, p.seq, targets.toMutableSet(), s, now())
    }

    fun tick() {
        wire.tick()
        if (now() - lastTelemetry >= 1000) {
            lastTelemetry = now()
            val traffic = wire.txBytes + wire.rxBytes
            if (traffic != lastTraffic) {
                lastTraffic = traffic
                log(
                    "load_traffic",
                    buildJsonObject {
                        put("txBytes", wire.txBytes)
                        put("rxBytes", wire.rxBytes)
                        put("queuedBytes", wire.queuedBytes)
                        put("peakQueuedBytes", wire.peakQueuedBytes)
                        put("sdkSendExceptions", wire.sendFailures)
                        put("corrupt", wire.corrupt)
                        put("pendingFanout", fans.size)
                    }
                        .toString(),
                )
            }
            val version = replica.epoch to replica.revision
            if (version != lastReplicaVersion) {
                lastReplicaVersion = version
                log(
                    "replica",
                    buildJsonObject {
                        put("epoch", replica.epoch)
                        put("revision", replica.revision)
                        put("counter", replica.counter)
                        put("bodyBytes", replica.data.length)
                        put("digest", replica.digest)
                    }
                        .toString(),
                )
            }
        }
        if (roleDue != null && now() >= roleDue!!) {
            roleDue = null
            migrationPhase = "reconnect"
            changeRole(roleHost)
        }
        if (migrationPhase == "prepare" && now() - migrationStart > 10_000)
            abortMigration("prepare_timeout")
        if (migrationPhase == "prepared" && now() - migrationStart > 20_000) {
            migration = "prepare_timeout"
            migrationPhase = ""
            preparedSnapshot = null
        }
        if (migrationPhase == "commit" && now() - migrationStart > 20_000) {
            migration = "commit_ack_timeout"
            migrationPhase = "reconnect"
            roleHost = false
            roleDue = now()
            log("migration_failed", "commit_ack_timeout; old host steps down")
        }
        if (migrationPhase == "reconnect" && now() - migrationStart > 120_000) {
            migration = "reconnect_timeout"
            migrationPhase = ""
            log("migration_failed", "reconnect_timeout")
        }
        if (expectedRemote.isNotEmpty() && now() >= remoteDeadline) {
            log("load_remote_timeout", expectedRemote.joinToString())
            expectedRemote.clear()
        }
        fans
            .filterValues { now() - it.start > 10_000 }
            .keys
            .toList()
            .forEach { key ->
                val fan = fans.remove(key)!!
                wire.enqueue(listOf(fan.origin), LoadPacket("nack", run = fan.run, seq = fan.seq))
                log("relay_timeout", key)
            }
        run?.let { r ->
            val expired =
                r.pending.filterValues { now() - it.sentAt >= r.plan.timeoutMs }.keys.toList()
            expired.forEach { key ->
                r.pending.remove(key)
                r.timeout++
                r.counts.getValue(key.first).timeout++
            }
            // A receipt timeout stops this stage; do not repeatedly refill an unknown SDK queue.
            if (expired.isNotEmpty()) {
                r.stopAt = now()
                batch.clear()
                wire.cancelData(r.id)
            }
            val stopSending = now() - r.start >= r.plan.seconds * 1000L || r.stopAt != null
            if (!stopSending) {
                for (attempt in 0 until 32) {
                    if (r.plan.rate != 0 && now() < r.nextDue) break
                    val estimate = (r.plan.bytes + 1200L) * r.targets.size
                    val inFlightBytes = r.pending.values.sumOf { it.bytes.toLong() }
                    if (
                        r.pending.size + r.targets.size > r.plan.window ||
                            inFlightBytes + estimate > LoadWire.BUDGET ||
                            !wire.canQueue(estimate)
                    ) {
                        r.limited++
                        r.nextDue = now().toDouble()
                        break
                    }
                    val seq = ++r.seq
                    val data =
                        buildString(r.plan.bytes) {
                            repeat(r.plan.bytes) {
                                append(('a'.code + ((it + seq) % 26).toInt()).toChar())
                            }
                        }
                    val packet =
                        LoadPacket(
                            if (r.plan.mode == LoadMode.ECHO) "probe" else "command",
                            r.id,
                            seq,
                            data = data,
                            digest = checksum(data),
                            size = data.length,
                        )
                    if (!wire.enqueue(r.targets, packet)) {
                        r.limited++
                        break
                    }
                    r.targets.forEach { ep ->
                        r.pending[ep to seq] = Pending(ep, now(), data.length)
                        r.sent++
                        r.counts.getValue(ep).sent++
                    }
                    r.peak = maxOf(r.peak, r.pending.size)
                    r.peakBytes = maxOf(r.peakBytes, wire.queuedBytes)
                    if (r.plan.rate != 0)
                        r.nextDue = maxOf(r.nextDue + 1000.0 / r.plan.rate, now() - 20.0)
                }
            } else if (r.pending.isEmpty() && wire.queuedBytes == 0)
                finish(if (r.timeout > 0) "timeout" else "completed")
            if (now() - lastProgress >= 1000 && run != null) {
                lastProgress = now()
                log(
                    "load_progress",
                    "${r.id}:sent=${r.sent},delivered=${r.ok},pending=${r.pending.size},queue=${wire.queuedBytes}",
                )
            }
        }
        wire.pump()
    }

    fun handoff(targetPeer: String) {
        require(isHost() && migrationPhase.isEmpty())
        val target = requireNotNull(identities[targetPeer]) { "Wait for peer hello" }
        cancel("migration")
        require(fans.isEmpty()) { "Wait for relay receipts" }
        migrationStart = now()
        migrationFinished = 0
        migrationTarget = target
        migrationId = "$nodeId-m${++txSequence}"
        migrationPhase = "prepare"
        migration = "prepare"
        migrationExpected = identities.values.toSet() + nodeId
        migrationAwait = peers().toMutableSet()
        val next = replica.copy(epoch = replica.epoch + 1)
        preparedSnapshot = next
        require(
            wire.enqueue(
                peers(),
                LoadPacket(
                    "prepare",
                    run = migrationId,
                    target = target,
                    snapshot = next,
                    digest = next.digest,
                    members = migrationExpected.toList(),
                ),
            ),
        )
        log("migration_prepare", "$migrationId:$target:${next.digest}")
    }

    private fun migrationPacket(peer: String, p: LoadPacket) {
        when (p.op) {
            "prepare" -> {
                require(!isHost())
                val s = requireNotNull(p.snapshot)
                require(s.digest == p.digest && s.epoch > replica.epoch)
                cancel("migration")
                migrationStart = now()
                migrationFinished = 0
                migrationId = p.run
                migrationTarget = p.target
                migrationExpected = p.members.toSet()
                migrationPhase = "prepared"
                migration = "prepared"
                preparedSnapshot = s
                wire.enqueue(listOf(peer), LoadPacket("ready", run = p.run, digest = s.digest))
                log("migration_prepared", "${p.run}:${s.digest}")
            }
            "ready" ->
                if (isHost() && migrationPhase == "prepare" && p.run == migrationId) {
                    require(p.digest == replica.copy(epoch = replica.epoch + 1).digest)
                    migrationAwait.remove(peer)
                    if (migrationAwait.isEmpty()) {
                        replica = replica.copy(epoch = replica.epoch + 1)
                        migrationPhase = "commit"
                        migration = "commit"
                        migrationAwait = peers().toMutableSet()
                        wire.enqueue(
                            peers(),
                            LoadPacket("commit", run = migrationId, target = migrationTarget),
                        )
                    }
                }
            "commit" ->
                if (!isHost() && p.run == migrationId && migrationPhase == "prepared") {
                    replica = requireNotNull(preparedSnapshot)
                    wire.enqueue(listOf(peer), LoadPacket("commit_ack", run = p.run))
                    migration = "reconnect"
                    migrationPhase = "reconnect"
                    roleHost = nodeId == migrationTarget
                    roleDue = now() + 1500
                    log("migration_commit", "$migrationId:newHost=$roleHost")
                }
            "commit_ack" ->
                if (isHost() && p.run == migrationId && migrationPhase == "commit") {
                    migrationAwait.remove(peer)
                    if (migrationAwait.isEmpty()) {
                        migrationPhase = "reconnect"
                        migration = "reconnect"
                        roleHost = false
                        roleDue = now() + 500
                    }
                }
            "abort" ->
                if (p.run == migrationId && migrationPhase == "prepared") {
                    migration = "aborted"
                    migrationPhase = ""
                    preparedSnapshot = null
                }
        }
    }

    private fun abortMigration(reason: String) {
        wire.enqueue(peers(), LoadPacket("abort", run = migrationId))
        migration = reason
        migrationPhase = ""
        preparedSnapshot = null
        log("migration_failed", reason)
    }

    private fun checkMigrationRecovered() {
        if (migrationPhase != "reconnect") return
        val present = identities.values.toSet() + nodeId
        val ready =
            if (isHost()) present.containsAll(migrationExpected)
            else migrationTarget in identities.values
        if (ready && (preparedSnapshot == null || replica.digest == preparedSnapshot!!.digest)) {
            migrationFinished = now()
            migrationPhase = ""
            migration = "recovered"
            log(
                "migration_recovered",
                "${migrationFinished - migrationStart}ms;hash=${replica.digest};nodes=${present.size}",
            )
        }
    }

    fun autoConnect(advertisedName: String): Boolean =
        migrationPhase == "reconnect" && !roleHost && advertisedName.endsWith("~$migrationTarget")

    fun takeOver() {
        require(!isHost() && peers().isEmpty()) { "Disconnect old host first" }
        cancel("takeover")
        replica = replica.copy(epoch = replica.epoch + 1)
        migrationPhase = ""
        migration = "manual_takeover"
        log("migration_takeover", "hash=${replica.digest};revision=${replica.revision}")
        changeRole(true)
    }
}
