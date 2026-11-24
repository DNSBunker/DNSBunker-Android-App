package org.dnsbunker.android.vpn

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.dnsbunker.android.core.DnsWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTcpProxyTest {
    private val client = DnsTcpSegment(
        ipVersion = 4,
        source = byteArrayOf(10, 0, 0, 2),
        destination = byteArrayOf(198.toByte(), 18, 0, 1),
        sourcePort = 42000,
        destinationPort = 53,
        sequence = 100,
        acknowledgement = 0,
        flags = DnsTcpPacket.SYN,
        window = 65535,
        payload = byteArrayOf(),
    )

    @Test fun `IPv4 DNS TCP replies have valid checksums and addresses`() {
        val wire = DnsTcpPacket.reply(client, 200, 101, DnsTcpPacket.SYN or DnsTcpPacket.ACK)
        val parsed = DnsTcpPacket.parse(wire, wire.size)
        assertNotNull(parsed)
        assertEquals(53, parsed!!.sourcePort)
        assertEquals(client.sourcePort, parsed.destinationPort)
        assertEquals(101L, parsed.acknowledgement)
        val damaged = wire.copyOf().apply { this[15] = (this[15].toInt() xor 1).toByte() }
        assertEquals(null, DnsTcpPacket.parse(damaged, damaged.size))
    }

    @Test fun `IPv6 DNS TCP replies have mandatory valid checksum`() {
        val v6 = client.copy(ipVersion = 6, source = ByteArray(16), destination = ByteArray(16))
        val wire = DnsTcpPacket.reply(v6, 500, 101, DnsTcpPacket.ACK, byteArrayOf(1, 2, 3))
        val parsed = DnsTcpPacket.parse(wire, wire.size)
        assertNotNull(parsed)
        assertEquals(6, parsed!!.ipVersion)
        assertTrue(parsed.payload.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun `TCP handshake and one DNS request returns framed response then FIN`() = runBlocking {
        val outgoing = mutableListOf<ByteArray>()
        val tcp = DnsTcpProxy(this, { DnsWire.servfail(it) }, { outgoing.add(it) })
        tcp.handle(client)
        val synAck = DnsTcpPacket.parse(outgoing.last(), outgoing.last().size)!!
        assertEquals(DnsTcpPacket.SYN or DnsTcpPacket.ACK, synAck.flags)

        val serverAck = (synAck.sequence + 1L) and 0xffff_ffffL
        val established = client.copy(sequence = 101, acknowledgement = serverAck, flags = DnsTcpPacket.ACK)
        tcp.handle(established)

        val query = DnsWire.healthQuery()
        val prefix = byteArrayOf((query.size ushr 8).toByte(), query.size.toByte())
        val framedQuery = prefix + query
        tcp.handle(established.copy(flags = DnsTcpPacket.ACK or DnsTcpPacket.PSH, payload = framedQuery.copyOfRange(0, 9)))
        tcp.handle(established.copy(sequence = 110, flags = DnsTcpPacket.ACK or DnsTcpPacket.PSH,
            payload = framedQuery.copyOfRange(9, framedQuery.size)))

        withTimeout(1000) { while (outgoing.size < 4) yield() }
        val answer = DnsTcpPacket.parse(outgoing.last(), outgoing.last().size)!!
        assertEquals(DnsTcpPacket.PSH or DnsTcpPacket.ACK, answer.flags)
        val dnsLength = ((answer.payload[0].toInt() and 255) shl 8) or (answer.payload[1].toInt() and 255)
        assertEquals(query.size, dnsLength)
        assertEquals(2, answer.payload[5].toInt() and 15)

        tcp.handle(established.copy(
            sequence = 101L + prefix.size + query.size,
            acknowledgement = (answer.sequence + answer.payload.size) and 0xffff_ffffL,
        ))
        val fin = DnsTcpPacket.parse(outgoing.last(), outgoing.last().size)!!
        assertTrue((fin.flags and DnsTcpPacket.FIN) != 0)
    }
}
