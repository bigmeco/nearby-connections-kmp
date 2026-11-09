package org.bigmeco.nearbylab

import kotlin.test.*
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class LoadWireTest {
    @Test
    fun controlPriorityPreservesControlOrder() {
        val ops = mutableListOf<String>()
        val wire =
            LoadWire(
                { 100 },
                { _, raw ->
                    ops += loadJson.decodeFromJsonElement<LoadPacket>(WireCodec.decode(raw).body).op
                },
                { _, _ -> },
                { fail(it) },
            )
        wire.enqueue(listOf("A"), LoadPacket("probe", run = "data"))
        wire.enqueue(listOf("A"), LoadPacket("start_clients", run = "job"))
        wire.enqueue(listOf("A"), LoadPacket("cancel_clients", target = "job"))
        wire.pump()
        assertEquals(listOf("start_clients", "cancel_clients", "probe"), ops)
    }

    @Test
    fun oneMebibyteSurvivesReorderedAndDuplicateChunks() {
        val frames = mutableListOf<String>()
        var received: LoadPacket? = null
        val sender = LoadWire({ 100 }, { _, raw -> frames += raw }, { _, _ -> }, { fail(it) })
        val receiver = LoadWire({ 100 }, { _, _ -> }, { _, p -> received = p }, { fail(it) })
        val packet =
            LoadPacket(
                "probe",
                run = "large",
                seq = 1,
                data = "x".repeat(1_048_576),
                size = 1_048_576,
            )
        assertTrue(sender.enqueue(listOf("B"), packet))
        while (sender.queuedBytes > 0) sender.pump()
        assertTrue(frames.size > 200)
        frames.forEach { assertTrue(it.encodeToByteArray().size <= WireCodec.MAX_BYTES) }
        val reversed = frames.reversed()
        val first = WireCodec.decode(reversed.first())
        receiver.incoming("A", reversed.first(), first.body)
        receiver.incoming("A", reversed.first(), first.body)
        reversed.drop(1).forEach { raw -> receiver.incoming("A", raw, WireCodec.decode(raw).body) }
        assertEquals(packet, received)
        assertEquals(1, receiver.duplicateChunks)
        assertEquals(0, receiver.corrupt)
    }

    @Test
    fun corruptChecksumIsRejectedAndQueueHasHardBudget() {
        val errors = mutableListOf<String>()
        var delivered = false
        val wire = LoadWire({ 100 }, { _, _ -> }, { _, _ -> delivered = true }, { errors += it })
        repeat(3) { index ->
            val p =
                LoadPacket(
                    "fragment",
                    run = "bad",
                    size = 10000,
                    parts = 3,
                    index = index,
                    data = "x".repeat(if (index < 2) 4500 else 1000),
                    digest = "bad",
                )
            wire.incoming("A", "raw", loadJson.encodeToJsonElement(p))
        }
        assertFalse(delivered)
        assertEquals(1, wire.corrupt)
        assertTrue(errors.isNotEmpty())
        val big = LoadPacket("probe", run = "q", data = "x".repeat(1_048_576))
        var accepted = 0
        while (wire.enqueue(listOf("A"), big)) {
            accepted++
            assertTrue(accepted < 10)
        }
        assertTrue(accepted > 0)
        assertTrue(wire.queuedBytes <= LoadWire.BUDGET)
        wire.cancelData("q")
        assertEquals(0, wire.queuedBytes)
    }
}
