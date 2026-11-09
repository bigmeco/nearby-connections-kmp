package org.bigmeco.nearbylab

import kotlin.test.*
import kotlinx.serialization.json.decodeFromJsonElement

class LoadTest {
    private class World {
        var time = 100L

        data class Message(val from: String, val to: String, val raw: String)

        val messages = ArrayDeque<Message>()
        val nodes = linkedMapOf<String, Node>()
        val held = mutableListOf<Message>()
        var hold: (Message, LoadPacket) -> Boolean = { _, _ -> false }

        inner class Node(val id: String, var host: Boolean) {
            val links = mutableSetOf<String>()
            val events = mutableListOf<Pair<String, String>>()
            val roles = mutableListOf<Boolean>()
            val engine: LoadEngine =
                LoadEngine(
                    { time },
                    { links.toList() },
                    { host },
                    { to, raw -> messages.addLast(Message(id, to, raw)) },
                    { k, v -> events += k to v },
                    { role ->
                        roles += role
                        links.toList().forEach { peer ->
                            nodes.getValue(peer).links.remove(id)
                            nodes.getValue(peer).engine.disconnected(id)
                        }
                        links.clear()
                        engineStop()
                        host = role
                    },
                )

            private fun engineStop() {
                engine.topologyStopped()
            }
        }

        fun node(id: String, host: Boolean = false) = Node(id, host).also { nodes[id] = it }

        fun connect(a: Node, b: Node) {
            a.links += b.id
            b.links += a.id
            a.engine.connected(b.id)
            b.engine.connected(a.id)
        }

        fun step(ms: Long = 20) {
            time += ms
            nodes.values.forEach { it.engine.tick() }
            var limit = 2000
            while (messages.isNotEmpty()) {
                check(--limit > 0)
                val m = messages.removeFirst()
                if (m.to !in nodes.getValue(m.from).links) continue
                val wire = WireCodec.decode(m.raw)
                val packet = loadJson.decodeFromJsonElement<LoadPacket>(wire.body)
                if (hold(m, packet)) held += m
                else nodes.getValue(m.to).engine.incomingWire(m.from, m.raw, wire.body)
            }
        }

        fun steps(n: Int) {
            repeat(n) { step() }
        }

        fun release() {
            messages.addAll(held)
            held.clear()
            hold = { _, _ -> false }
        }
    }

    @Test
    fun largeMessagesReassembleAndReportApplicationBytes() {
        val w = World()
        val host = w.node("H", true)
        val client = w.node("C")
        w.connect(host, client)
        w.steps(5)
        client.engine.start(LoadPlan(bytes = 65536, rate = 5, seconds = 1, window = 8))
        w.steps(160)
        val result = client.engine.state().results.single()
        assertEquals("completed", result.reason)
        assertEquals(5, result.sent)
        assertEquals(result.sent, result.delivered)
        assertEquals(result.delivered * 65536, result.deliveredBytes)
        assertEquals(0, result.timedOut)
        assertEquals(0, result.corrupt)
        assertTrue(result.wireTxBytes > result.deliveredBytes)
        assertEquals(result.delivered, result.latency.samples)
        assertTrue(result.peakQueuedBytes <= LoadWire.BUDGET)
    }

    @Test
    fun timeoutIsNotHiddenInLatencyAndWindowRemainsBounded() {
        val w = World()
        val host = w.node("H", true)
        val client = w.node("C")
        w.connect(host, client)
        w.steps(5)
        w.hold = { _, p -> p.op == "receipt" }
        client.engine.start(
            LoadPlan(bytes = 256, rate = 0, seconds = 1, window = 4, timeoutMs = 1000),
        )
        w.steps(120)
        val r = client.engine.state().results.single()
        assertEquals(4, r.sent)
        assertEquals(4, r.timedOut)
        assertEquals(0, r.delivered)
        assertEquals(0, r.latency.samples)
        assertEquals(4, r.peakPending)
        assertTrue(r.limitedTicks > 0)
        assertEquals(r.sent, r.delivered + r.timedOut + r.failed + r.cancelled)
    }

    @Test
    fun relayReceiptWaitsForEveryRecipient() {
        val w = World()
        val host = w.node("H", true)
        val a = w.node("A")
        val b = w.node("B")
        w.connect(host, a)
        w.connect(host, b)
        w.steps(8)
        w.hold = { m, p -> m.from == "B" && p.op == "applied" }
        a.engine.start(LoadPlan(LoadMode.RELAY, 128, 1, 1, 1))
        w.steps(60)
        assertEquals(0, a.engine.state().delivered)
        assertEquals(1, a.engine.state().pending)
        assertEquals(host.engine.state().replicaHash, b.engine.state().replicaHash)
        w.release()
        w.steps(10)
        val r = a.engine.state().results.single()
        assertEquals(1, r.delivered)
        assertTrue(r.latency.min >= 1200)
        assertEquals(1, host.engine.state().replica.counter)
    }

    @Test
    fun concurrentClientsConvergeWithoutLosingCommands() {
        val w = World()
        val host = w.node("H", true)
        val a = w.node("A")
        val b = w.node("B")
        w.connect(host, a)
        w.connect(host, b)
        w.steps(8)
        host.engine.startClients(LoadPlan(LoadMode.RELAY, 1024, 10, 1, 8))
        w.steps(200)
        assertEquals(2, host.engine.state().remoteResults.size)
        host.engine.state().remoteResults.forEach {
            assertEquals(10, it.delivered)
            assertEquals(0, it.timedOut)
            assertEquals(0, it.failed)
        }
        assertEquals(20, host.engine.state().replica.counter)
        assertEquals(host.engine.state().replicaHash, a.engine.state().replicaHash)
        assertEquals(host.engine.state().replicaHash, b.engine.state().replicaHash)
    }

    @Test
    fun plannedHandoffPreservesStateAndWaitsForNewConnections() {
        val w = World()
        val host = w.node("H", true)
        val a = w.node("A")
        val b = w.node("B")
        w.connect(host, a)
        w.connect(host, b)
        w.steps(8)
        a.engine.start(LoadPlan(LoadMode.RELAY, 128, 1, 1, 1))
        w.steps(80)
        host.engine.handoff("A")
        w.steps(100)
        assertEquals(listOf(true), a.roles)
        assertEquals(listOf(false), host.roles)
        assertEquals("reconnect", a.engine.state().migration)
        assertEquals(1, a.engine.state().replica.counter)
        assertEquals(1, a.engine.state().replica.epoch)
        w.connect(a, host)
        w.connect(a, b)
        w.steps(10)
        assertEquals("recovered", a.engine.state().migration)
        assertEquals(a.engine.state().replicaHash, host.engine.state().replicaHash)
        assertEquals(a.engine.state().replicaHash, b.engine.state().replicaHash)
    }

    @Test
    fun abortedPreparationDoesNotApplyUncommittedEpoch() {
        val w = World()
        val host = w.node("H", true)
        val client = w.node("C")
        w.connect(host, client)
        w.steps(5)
        w.hold = { _, p -> p.op == "ready" }
        host.engine.handoff("C")
        w.steps(600)
        assertEquals(0, host.engine.state().replica.epoch)
        assertEquals(0, client.engine.state().replica.epoch)
        assertTrue(client.roles.isEmpty())
    }

    @Test
    fun manualTakeoverRequiresOldHostDisconnected() {
        val w = World()
        val host = w.node("H", true)
        val client = w.node("C")
        w.connect(host, client)
        w.steps(5)
        assertFails { client.engine.takeOver() }
        host.links.clear()
        client.links.clear()
        client.engine.disconnected("H")
        client.engine.takeOver()
        assertEquals(listOf(true), client.roles)
        assertEquals(1, client.engine.state().replica.epoch)
    }

    @Test
    fun histogramNearestRankIncludesTail() {
        val h = Histogram()
        (1..100).forEach { h.add(it.toLong()) }
        assertEquals(Latency(100, 1, 50, 95, 99, 100), h.value())
    }
}
