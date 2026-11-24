package org.dnsbunker.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.dnsbunker.android.core.DnsWire

class DnsUdpPacketTest {
    private val answer = ByteArray(20) { it.toByte() }

    @Test fun `IPv4 UDP replies use reversed addresses and expected ports`() {
        val request = DnsUdpRequest(
            ipVersion = 4,
            source = byteArrayOf(10, 0, 0, 2),
            destination = byteArrayOf(10, 0, 0, 1),
            sourcePort = 53123,
            dnsMessage = answer,
        )
        val raw = DnsUdpPacket.response(request, answer)
        assertEquals(48, raw.size)
        assertEquals(0x45, raw[0].toInt() and 255)
        assertEquals(53, (raw[20].toInt() and 255) * 256 + (raw[21].toInt() and 255))
        assertEquals(10, raw[12].toInt() and 255)
        assertTrue(raw.copyOfRange(28, 48).contentEquals(answer))
    }

    @Test fun `IPv6 replies include mandatory UDP checksum`() {
        val request = DnsUdpRequest(
            ipVersion = 6,
            source = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 2 },
            destination = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 1 },
            sourcePort = 5050,
            dnsMessage = answer,
        )
        val raw = DnsUdpPacket.response(request, answer)
        assertEquals(68, raw.size)
        assertEquals(0x60, raw[0].toInt() and 255)
        assertTrue(raw[46] != 0.toByte() || raw[47] != 0.toByte())
    }

    @Test fun `IPv4 TUN parser accepts valid checksums but rejects tampering`() {
        val query = DnsWire.healthQuery()
        val reverse = DnsUdpRequest(4,
            byteArrayOf(198.toByte(), 18, 0, 1), byteArrayOf(198.toByte(), 18, 0, 2), 53, query)
        val wire = DnsUdpPacket.response(reverse, query)
        assertTrue(DnsUdpPacket.parse(wire, wire.size)!!.dnsMessage.contentEquals(query))
        val tampered = wire.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertNull(DnsUdpPacket.parse(tampered, tampered.size))
    }

    @Test fun `IPv6 TUN parser validates pseudoheader checksum`() {
        val query = DnsWire.healthQuery()
        val reverse = DnsUdpRequest(6,
            ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 1 },
            ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 2 }, 53, query)
        val wire = DnsUdpPacket.response(reverse, query)
        assertTrue(DnsUdpPacket.parse(wire, wire.size)!!.dnsMessage.contentEquals(query))
        wire[46] = 0; wire[47] = 0
        assertNull(DnsUdpPacket.parse(wire, wire.size))
    }
}
