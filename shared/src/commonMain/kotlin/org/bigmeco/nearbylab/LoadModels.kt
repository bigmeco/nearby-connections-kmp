package org.bigmeco.nearbylab

import kotlin.math.ceil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LoadMode {
    ECHO,
    RELAY,
}

@Serializable
data class LoadPlan(
    val mode: LoadMode = LoadMode.ECHO,
    val bytes: Int = 1024,
    val rate: Int = 10,
    val seconds: Int = 10,
    val window: Int = 16,
    val timeoutMs: Long = 10_000,
) {
    fun validate() {
        require(bytes in 64..1_048_576 && rate in 0..1000 && seconds in 1..600 && window in 1..128)
        require(timeoutMs in 1000..60_000)
    }
}

@Serializable
data class GameSnapshot(
    val epoch: Long = 0,
    val revision: Long = 0,
    val counter: Long = 0,
    val data: String = "",
) {
    val digest: String
        get() = checksum("$epoch/$revision/$counter/$data")
}

@Serializable
data class LoadPacket(
    val op: String,
    val run: String = "",
    val seq: Long = 0,
    val node: String = "",
    val data: String = "",
    val digest: String = "",
    val size: Int = 0,
    val index: Int = 0,
    val parts: Int = 0,
    val snapshot: GameSnapshot? = null,
    val members: List<String> = emptyList(),
    val target: String = "",
    val plan: LoadPlan? = null,
    val result: LoadResult? = null,
)

@Serializable
data class Latency(
    val samples: Long = 0,
    val min: Long = 0,
    val p50: Long = 0,
    val p95: Long = 0,
    val p99: Long = 0,
    val max: Long = 0,
)

@Serializable
data class PeerLoadResult(
    val endpoint: String,
    val sent: Long,
    val delivered: Long,
    val timeout: Long,
    val failed: Long,
    val latency: Latency,
)

@Serializable
data class LoadResult(
    val run: String,
    val plan: LoadPlan,
    val reason: String,
    val elapsedMs: Long,
    val sent: Long,
    val delivered: Long,
    val timedOut: Long,
    val failed: Long,
    val cancelled: Long,
    val deliveredBytes: Long,
    val wireTxBytes: Long,
    val wireRxBytes: Long,
    val peakPending: Int,
    val peakQueuedBytes: Int,
    val limitedTicks: Long,
    val duplicates: Long,
    val outOfOrder: Long,
    val corrupt: Long,
    val latency: Latency,
    val peers: List<PeerLoadResult> = emptyList(),
) {
    val goodputBytesPerSecond: Long
        get() = if (elapsedMs > 0) deliveredBytes * 1000 / elapsedMs else 0
}

data class LoadState(
    val running: Boolean = false,
    val draining: Boolean = false,
    val run: String = "",
    val plan: LoadPlan = LoadPlan(),
    val sent: Long = 0,
    val delivered: Long = 0,
    val pending: Int = 0,
    val queuedBytes: Int = 0,
    val progressMs: Long = 0,
    val wireTxBytes: Long = 0,
    val wireRxBytes: Long = 0,
    val received: Long = 0,
    val corrupt: Long = 0,
    val duplicates: Long = 0,
    val results: List<LoadResult> = emptyList(),
    val remoteResults: List<LoadResult> = emptyList(),
    val replica: GameSnapshot = GameSnapshot(),
    val replicaHash: String = GameSnapshot().digest,
    val nodeId: String = "",
    val identities: Map<String, String> = emptyMap(),
    val migration: String = "",
    val migrationElapsedMs: Long = 0,
    val migrationExpected: Int = 0,
    val migrationConnected: Int = 0,
    val remotePending: Int = 0,
)

internal val loadJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}
internal val reportJson = Json { encodeDefaults = true }

internal fun checksum(data: String): String {
    var hash = 0x811c9dc5u
    data.encodeToByteArray().forEach { hash = (hash xor it.toUByte().toUInt()) * 0x01000193u }
    return hash.toString(16)
}

internal class Histogram {
    private val bins = IntArray(60_001)
    private var count = 0L
    private var min = Long.MAX_VALUE
    private var max = 0L

    fun add(ms: Long) {
        val n = ms.coerceAtLeast(0)
        count++
        min = minOf(min, n)
        max = maxOf(max, n)
        bins[n.coerceAtMost(60_000).toInt()]++
    }

    private fun percentile(q: Double): Long {
        val rank = ceil(count * q).toLong()
        var sum = 0L
        bins.forEachIndexed { i, n ->
            sum += n
            if (sum >= rank) return i.toLong()
        }
        return max
    }

    fun value() =
        if (count == 0L) Latency()
        else Latency(count, min, percentile(.5), percentile(.95), percentile(.99), max)
}
