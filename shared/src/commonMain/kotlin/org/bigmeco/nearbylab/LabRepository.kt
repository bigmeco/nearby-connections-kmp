package org.bigmeco.nearbylab

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class PeerStatus {
    FOUND,
    CONNECTING,
    VERIFY,
    CONNECTED,
    DISCONNECTED,
    REJECTED,
}

enum class LabMode {
    IDLE,
    HOST,
    DISCOVER,
}

data class Peer(
    val id: String,
    val name: String,
    val status: PeerStatus = PeerStatus.FOUND,
    val code: String = "",
    val rttMs: Long? = null,
)

data class LabState(
    val mode: LabMode = LabMode.IDLE,
    val peers: List<Peer> = emptyList(),
    val logs: List<String> = emptyList(),
    val load: LoadState = LoadState(),
)

interface LabRepository {
    val state: StateFlow<LabState>

    fun start(host: Boolean, name: String)

    fun connect(id: String)

    fun verify(id: String, accept: Boolean)

    fun disconnect(id: String)

    fun send(text: String, json: Boolean)

    fun ping()

    fun tick()

    fun stop()

    fun share()

    fun load(plan: LoadPlan, suite: Boolean = false, clients: Boolean = false)

    fun cancelLoad()

    fun handoff(id: String)

    fun takeOver()
}

class LabRepositoryImpl(private val transport: NearbyTransport) : LabRepository, TransportObserver {
    private val mutable = MutableStateFlow(LabState())
    override val state = mutable.asStateFlow()
    private var sequence = 0L
    private val clock = TimeSource.Monotonic.markNow()
    private val pending = mutableMapOf<Pair<String, String>, Long>()
    private var roomName = "NearbyLab"
    private var lastPublish = 0L
    private val loadEngine: LoadEngine =
        LoadEngine(
            ::now,
            { connected().map { it.id } },
            { state.value.mode == LabMode.HOST },
            { id, raw -> transport.send(id, raw) },
            { kind, value -> log(kind, value = value) },
            { host -> start(host, roomName) },
        )

    init {
        transport.setObserver(this)
    }

    private fun now() = clock.elapsedNow().inWholeMilliseconds

    private fun log(kind: String, endpoint: String = "", value: String = "") {
        val line = buildJsonObject {
            put("elapsedMs", now())
            put("kind", kind)
            put("endpoint", endpoint)
            put("value", value)
        }
            .toString()
        mutable.update { it.copy(logs = (it.logs + line).takeLast(500)) }
        transport.appendLog(line)
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("error", value = e.message ?: e.toString())
        }
    }

    private fun peer(id: String, transform: (Peer) -> Peer) {
        mutable.update { s ->
            s.copy(
                peers =
                    s.peers.filterNot { it.id == id } +
                        transform(s.peers.find { it.id == id } ?: Peer(id, id)),
            )
        }
    }

    override fun start(host: Boolean, name: String) = safe {
        require(name.isNotBlank() && name.encodeToByteArray().size <= 64) {
            "Name must be 1–64 UTF-8 bytes"
        }
        roomName = name
        stop()
        mutable.update {
            it.copy(mode = if (host) LabMode.HOST else LabMode.DISCOVER, peers = emptyList())
        }
        log("session", value = if (host) "host: $name" else "discover: $name")
        val advertised = NearbyConfig.PREFIX + name + "~" + loadEngine.nodeId
        if (host) transport.host(advertised) else transport.discover(advertised)
    }

    override fun connect(id: String) = safe {
        require(state.value.mode == LabMode.DISCOVER)
        require(state.value.peers.any { it.id == id && it.status == PeerStatus.FOUND }) {
            "Rediscover endpoint before reconnecting"
        }
        peer(id) { it.copy(status = PeerStatus.CONNECTING) }
        transport.connect(id)
    }

    override fun verify(id: String, accept: Boolean) = safe {
        require(state.value.peers.any { it.id == id && it.status == PeerStatus.VERIFY })
        transport.verify(id, accept)
        peer(id) {
            it.copy(status = if (accept) PeerStatus.CONNECTING else PeerStatus.REJECTED, code = "")
        }
        log("verification", id, accept.toString())
    }

    override fun disconnect(id: String) = safe {
        transport.disconnect(id)
        clear(id)
        peer(id) { it.copy(status = PeerStatus.DISCONNECTED, code = "") }
        log("disconnect_requested", id)
    }

    private fun clear(id: String) {
        pending.keys.removeAll { it.first == id }
        loadEngine.disconnected(id)
    }

    private fun connected() = state.value.peers.filter { it.status == PeerStatus.CONNECTED }

    override fun send(text: String, json: Boolean) = safe {
        require(connected().isNotEmpty()) { "No connected peers" }
        val message =
            WireMessage(
                id = (++sequence).toString(),
                type = if (json) "json" else "text",
                body = if (json) WireCodec.jsonBody(text) else JsonPrimitive(text),
            )
        val raw = WireCodec.encode(message)
        connected().forEach {
            transport.send(it.id, raw)
            log("send", it.id, raw)
        }
    }

    override fun ping() = safe {
        require(connected().isNotEmpty()) { "No connected peers" }
        connected().forEach {
            val id = (++sequence).toString()
            pending[it.id to id] = now()
            transport.send(it.id, WireCodec.encode(WireMessage(id = id, type = "ping")))
            log("ping", it.id, id)
        }
    }

    override fun tick() {
        safe { loadEngine.tick() }
        if (now() - lastPublish >= 250) {
            lastPublish = now()
            publishLoad()
        }
        val expired = pending.filterValues { now() - it >= 5_000 }.keys.toList()
        expired.forEach {
            pending.remove(it)
            peer(it.first) { p -> p.copy(rttMs = null) }
            log("ping_timeout", it.first, it.second)
        }
    }

    override fun stop() = safe {
        loadEngine.topologyStopped()
        transport.stop()
        pending.clear()
        mutable.update {
            it.copy(
                mode = LabMode.IDLE,
                peers =
                    it.peers.map { p ->
                        p.copy(status = PeerStatus.DISCONNECTED, code = "", rttMs = null)
                    },
            )
        }
        log("stopped")
    }

    override fun share() = safe { transport.shareLog() }

    private fun publishLoad() {
        mutable.update { it.copy(load = loadEngine.state()) }
    }

    override fun load(plan: LoadPlan, suite: Boolean, clients: Boolean) = safe {
        if (clients) loadEngine.startClients(plan) else loadEngine.start(plan, suite)
        publishLoad()
    }

    override fun cancelLoad() = safe {
        loadEngine.cancel()
        publishLoad()
    }

    override fun handoff(id: String) = safe {
        loadEngine.handoff(id)
        publishLoad()
    }

    override fun takeOver() = safe {
        loadEngine.takeOver()
        publishLoad()
    }

    override fun onEvent(kind: String, endpointId: String, value: String) = safe {
        // Bulk content and successful SDK progress callbacks must not become the benchmark's disk
        // workload.
        if (kind != "bytes" && kind != "transfer" && !(kind == "info" && value.startsWith("send:")))
            log(kind, endpointId, value)
        when (kind) {
            "found" ->
                if (value.startsWith(NearbyConfig.PREFIX) && state.value.mode != LabMode.IDLE) {
                    peer(endpointId) {
                        if (
                            it.status in
                                listOf(
                                    PeerStatus.CONNECTED,
                                    PeerStatus.VERIFY,
                                    PeerStatus.CONNECTING,
                                )
                        )
                            it
                        else
                            it.copy(
                                name =
                                    value
                                        .removePrefix(NearbyConfig.PREFIX)
                                        .substringBeforeLast("~"),
                                status = PeerStatus.FOUND,
                                code = "",
                            )
                    }
                    if (loadEngine.autoConnect(value)) connect(endpointId)
                }
            "name" ->
                peer(endpointId) {
                    it.copy(name = value.removePrefix(NearbyConfig.PREFIX).substringBeforeLast("~"))
                }
            "lost" ->
                if (state.value.peers.any { it.id == endpointId })
                    peer(endpointId) {
                        if (it.status == PeerStatus.FOUND) it.copy(status = PeerStatus.DISCONNECTED)
                        else it
                    }
            "verify" -> peer(endpointId) { it.copy(code = value, status = PeerStatus.VERIFY) }
            "connected" -> {
                peer(endpointId) { it.copy(status = PeerStatus.CONNECTED, code = "", rttMs = null) }
                loadEngine.connected(endpointId)
            }
            "disconnected",
            "rejected" -> {
                clear(endpointId)
                if (state.value.peers.any { it.id == endpointId })
                    peer(endpointId) {
                        it.copy(
                            status =
                                if (kind == "rejected") PeerStatus.REJECTED
                                else PeerStatus.DISCONNECTED,
                            code = "",
                            rttMs = null,
                        )
                    }
            }
            "bytes" -> {
                require(
                    state.value.peers.any {
                        it.id == endpointId && it.status == PeerStatus.CONNECTED
                    },
                ) {
                    "Payload from unverified endpoint"
                }
                val message = WireCodec.decode(value)
                if (message.type != "lab") log(kind, endpointId, value)
                when (message.type) {
                    "lab" -> loadEngine.incomingWire(endpointId, value, message.body)
                    "ping" ->
                        transport.send(endpointId, WireCodec.encode(message.copy(type = "pong")))
                    "pong" ->
                        pending.remove(endpointId to message.id)?.let { sent ->
                            peer(endpointId) { it.copy(rttMs = now() - sent) }
                            log("rtt_ms", endpointId, (now() - sent).toString())
                        }
                    "text",
                    "json" ->
                        transport.send(
                            endpointId,
                            WireCodec.encode(WireMessage(id = message.id, type = "ack")),
                        )
                }
            }
        }
    }
}
