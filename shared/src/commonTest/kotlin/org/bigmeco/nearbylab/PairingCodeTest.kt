package org.bigmeco.nearbylab

import kotlin.test.*

class PairingCodeTest {
    // Fixed vectors for the actual pinned SDK algorithm, including URL-safe alphabet and case.
    @Test
    fun matchesPinnedCppFormat() {
        assertEquals("AAAAA", PairingCode.fromRawToken(ByteArray(32)))
        assertEquals("_____", PairingCode.fromRawToken(ByteArray(32) { 0xff.toByte() }))
        assertEquals("AAECA", PairingCode.fromRawToken(ByteArray(32) { it.toByte() }))
        assertEquals("AGVSB", PairingCode.fromRawToken("hello".encodeToByteArray().copyOf(32)))
        assertEquals(
            "-____",
            PairingCode.fromRawToken(
                ByteArray(32) { if (it == 0) 0xfb.toByte() else 0xff.toByte() },
            ),
        )
    }

    @Test
    fun rejectsMissingOrTruncatedRawTokens() {
        listOf(0, 3, 5, 31, 33).forEach { size ->
            assertFails { PairingCode.fromRawToken(ByteArray(size)) }
        }
    }
}
