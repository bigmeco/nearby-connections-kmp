package org.bigmeco.nearbylab

import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive

class LabTest {
    private class Fake : NearbyTransport {
        lateinit var listener: TransportObserver
        val sent = mutableListOf<Pair<String, String>>()
        val approvals = mutableListOf<Boolean>()

        override fun setObserver(observer: TransportObserver) {
            listener = observer
        }

        override fun host(name: String) {}

        override fun discover(name: String) {}

        override fun connect(endpointId: String) {}

        override fun verify(endpointId: String, accept: Boolean) {
            approvals += accept
        }

        override fun send(endpointId: String, message: String) {
            sent += endpointId to message
        }

        override fun disconnect(endpointId: String) {}

        override fun stop() {}

        override fun appendLog(entry: String) {}

        override fun shareLog() {}
    }

    @Test
    fun unicodeAndJsonRoundTrip() {
        val m = WireMessage(id = "1", type = "text", body = JsonPrimitive("Привет 📱"))
        assertEquals(m, WireCodec.decode(WireCodec.encode(m)))
        val nested =
            WireMessage(
                id = "2",
                type = "json",
                body = WireCodec.jsonBody("{\"players\":[1,2],\"secret\":null}"),
            )
        assertEquals(nested, WireCodec.decode(WireCodec.encode(nested)))
    }

    @Test
    fun rejectsUnsupportedMalformedAndOversizedFrames() {
        assertFails { WireCodec.decode("{\"v\":2,\"id\":\"x\",\"type\":\"ping\"}") }
        assertFails { WireCodec.decode("{\"id\":\"x\",\"type\":\"unknown\"}") }
        assertFails { WireCodec.decode("bad json") }
        assertFails {
            WireCodec.encode(
                WireMessage(id = "x", type = "text", body = JsonPrimitive("я".repeat(9000))),
            )
        }
        assertFails { WireCodec.decode("{\"id\":\"x\",\"type\":\"text\",\"body\":{}}") }
    }

    @Test
    fun verificationRequiresExplicitAction() {
        val t = Fake()
        val r = LabRepositoryImpl(t)
        t.listener.onEvent("name", "A", "NearbyLab|iPhone")
        t.listener.onEvent("verify", "A", "1234")
        assertTrue(t.approvals.isEmpty())
        assertEquals(PeerStatus.VERIFY, r.state.value.peers.single().status)
        r.verify("A", false)
        assertEquals(listOf(false), t.approvals)
        assertEquals(PeerStatus.REJECTED, r.state.value.peers.single().status)
    }

    @Test
    fun multiplePeersBroadcastAndPingCorrelation() {
        val t = Fake()
        val r = LabRepositoryImpl(t)
        listOf("A", "B").forEach { t.listener.onEvent("connected", it, "") }
        r.send("hello", false)
        assertEquals(setOf("A", "B"), t.sent.map { it.first }.toSet())
        r.ping()
        val ping = t.sent.last()
        t.listener.onEvent(
            "bytes",
            "A",
            WireCodec.encode(WireCodec.decode(ping.second).copy(type = "pong")),
        )
        assertNull(r.state.value.peers.first { it.id == "A" }.rttMs)
        t.listener.onEvent(
            "bytes",
            ping.first,
            WireCodec.encode(WireCodec.decode(ping.second).copy(type = "pong")),
        )
        assertNotNull(r.state.value.peers.first { it.id == ping.first }.rttMs)
        t.listener.onEvent("disconnected", ping.first, "")
        assertNull(r.state.value.peers.first { it.id == ping.first }.rttMs)
    }

    @Test
    fun unverifiedPayloadIsNotAnsweredAndRediscoveryIsRequired() {
        val t = Fake()
        val r = LabRepositoryImpl(t)
        t.listener.onEvent(
            "bytes",
            "stranger",
            WireCodec.encode(WireMessage(id = "1", type = "ping")),
        )
        assertTrue(t.sent.isEmpty())
        r.start(false, "test")
        t.listener.onEvent("found", "A", "NearbyLab|room")
        r.connect("A")
        t.listener.onEvent("disconnected", "A", "")
        r.connect("A")
        assertEquals(PeerStatus.DISCONNECTED, r.state.value.peers.single().status)
        t.listener.onEvent("found", "NEW", "NearbyLab|room")
        assertEquals(PeerStatus.FOUND, r.state.value.peers.last().status)
        r.stop()
        assertTrue(r.state.value.peers.all { it.status == PeerStatus.DISCONNECTED })
    }

    @Test
    fun lateDisconnectDoesNotResurrectPeerAfterNewSession() {
        val t = Fake()
        val r = LabRepositoryImpl(t)
        r.start(true, "old")
        t.listener.onEvent("connected", "OLD", "")
        r.start(false, "new")
        t.listener.onEvent("disconnected", "OLD", "")
        t.listener.onEvent("lost", "OLD", "")
        assertTrue(r.state.value.peers.isEmpty())
    }
}
