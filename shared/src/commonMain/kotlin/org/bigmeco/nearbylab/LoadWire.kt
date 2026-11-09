package org.bigmeco.nearbylab

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A bounded, round-robin application queue. SDK queues are measured indirectly by receipts. */
internal class LoadWire(
    private val now: () -> Long,
    private val send: (String, String) -> Unit,
    private val receive: (String, LoadPacket) -> Unit,
    private val failure: (String) -> Unit,
) {
    companion object {
        const val CHUNK = 4500
        const val BUDGET = 8 * 1024 * 1024
        const val MAX_MESSAGE = 2 * 1024 * 1024
    }

    private data class Out(
        val peer: String,
        val id: String,
        val raw: String,
        val hash: String,
        val run: String,
        val dataPacket: Boolean,
        var cursor: Int = 0,
    )

    private data class Assembly(
        val peer: String,
        val id: String,
        val size: Int,
        val hash: String,
        val chunks: Array<String?>,
        val started: Long,
        var used: Int = 0,
    )

    private val queue = ArrayDeque<Out>()
    private val assemblies = mutableMapOf<Pair<String, String>, Assembly>()
    private var assemblyBytes = 0
    private var sequence = 0L
    var queuedBytes = 0
        private set

    var peakQueuedBytes = 0
        private set

    var txBytes = 0L
        private set

    var rxBytes = 0L
        private set

    var corrupt = 0L
        private set

    var duplicateChunks = 0L
        private set

    var sendFailures = 0L
        private set

    fun canQueue(bytes: Long) = bytes <= BUDGET - queuedBytes && queue.size < 512

    fun enqueue(peers: List<String>, packet: LoadPacket): Boolean {
        val raw = loadJson.encodeToString(packet)
        require(raw.length <= MAX_MESSAGE)
        if (!canQueue(raw.length.toLong() * peers.size) || queue.size + peers.size > 512)
            return false
        val hash = if (raw.length > CHUNK) checksum(raw) else ""
        val dataPacket = packet.op in setOf("probe", "command", "state")
        peers.forEach {
            val out = Out(it, (++sequence).toString(), raw, hash, packet.run, dataPacket)
            // Control frames precede data, but preserve FIFO among control operations.
            val firstData = if (dataPacket) -1 else queue.indexOfFirst { it.dataPacket }
            if (firstData < 0) queue.addLast(out) else queue.add(firstData, out)
            queuedBytes += raw.length
        }
        peakQueuedBytes = maxOf(peakQueuedBytes, queuedBytes)
        return true
    }

    fun pump() {
        repeat(32) {
            if (queue.isEmpty()) return
            val out = queue.removeFirst()
            val last = minOf(out.cursor + CHUNK, out.raw.length)
            val packet =
                if (out.raw.length <= CHUNK) loadJson.decodeFromString<LoadPacket>(out.raw)
                else
                    LoadPacket(
                        op = "fragment",
                        run = out.id,
                        data = out.raw.substring(out.cursor, last),
                        digest = out.hash,
                        size = out.raw.length,
                        index = out.cursor / CHUNK,
                        parts = (out.raw.length + CHUNK - 1) / CHUNK,
                    )
            val raw =
                WireCodec.encode(
                    WireMessage(
                        id = "L${out.id}",
                        type = "lab",
                        body = loadJson.encodeToJsonElement(packet),
                    ),
                )
            try {
                send(out.peer, raw)
                txBytes += raw.encodeToByteArray().size
            } catch (e: Exception) {
                sendFailures++
                failure("send:${out.peer}:${e.message}")
            }
            queuedBytes -= last - out.cursor
            out.cursor = last
            if (last < out.raw.length) queue.addLast(out)
        }
    }

    fun incoming(peer: String, raw: String, body: kotlinx.serialization.json.JsonElement) {
        rxBytes += raw.encodeToByteArray().size
        try {
            val p = loadJson.decodeFromJsonElement<LoadPacket>(body)
            if (p.op != "fragment") {
                receive(peer, p)
                return
            }
            require(
                p.size in (CHUNK + 1)..MAX_MESSAGE &&
                    p.parts == (p.size + CHUNK - 1) / CHUNK &&
                    p.index in 0 until p.parts,
            )
            require(p.data.length == minOf(CHUNK, p.size - p.index * CHUNK))
            val key = peer to p.run
            val a =
                assemblies[key]
                    ?: run {
                        require(assemblies.size < 64 && assemblyBytes + p.size <= BUDGET)
                        Assembly(peer, p.run, p.size, p.digest, arrayOfNulls(p.parts), now()).also {
                            assemblies[key] = it
                            assemblyBytes += p.size
                        }
                    }
            require(a.size == p.size && a.hash == p.digest && a.chunks.size == p.parts)
            if (a.chunks[p.index] != null) {
                duplicateChunks++
                return
            }
            a.chunks[p.index] = p.data
            a.used++
            if (a.used == a.chunks.size) {
                assemblies.remove(key)
                assemblyBytes -= a.size
                val joined = a.chunks.joinToString("") { requireNotNull(it) }
                require(joined.length == a.size && checksum(joined) == a.hash)
                val packet = loadJson.decodeFromString<LoadPacket>(joined)
                require(packet.op != "fragment")
                receive(peer, packet)
            }
        } catch (e: Exception) {
            corrupt++
            failure("reassembly:${e.message}")
        }
    }

    fun cancelData(run: String) {
        val kept = queue.filterNot { it.dataPacket && it.run == run }
        queue.clear()
        queue.addAll(kept)
        queuedBytes = queue.sumOf { it.raw.length - it.cursor }
    }

    fun tick() {
        val expired = assemblies.filterValues { now() - it.started > 60_000 }.keys.toList()
        expired.forEach {
            val a = assemblies.remove(it)!!
            assemblyBytes -= a.size
            failure("reassembly_timeout:${a.peer}:${a.id}")
        }
    }

    fun clear() {
        queue.clear()
        assemblies.clear()
        queuedBytes = 0
        assemblyBytes = 0
    }
}
