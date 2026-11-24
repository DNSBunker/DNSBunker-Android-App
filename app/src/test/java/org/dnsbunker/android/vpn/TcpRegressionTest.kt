package org.dnsbunker.android.vpn

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.dnsbunker.android.core.DnsWire
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TcpRegressionTest {
    private fun syn(version: Int = 4, port: Int = 42000) = DnsTcpSegment(version,
        if (version == 4) byteArrayOf(10, 0, 0, 2) else ByteArray(16).apply { this[15] = 2 },
        if (version == 4) byteArrayOf(198.toByte(), 18, 0, 1) else ByteArray(16).apply { this[15] = 1 },
        port, 53, 0xffff_ffffL, 0, DnsTcpPacket.SYN, 65535, byteArrayOf())
    private fun parse(bytes: ByteArray) = DnsTcpPacket.parse(bytes, bytes.size)!!
    private fun frame(query: ByteArray) = byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()) + query

    @Test fun `SYN retransmission preserves server sequence and wraps client sequence`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val proxy = DnsTcpProxy(backgroundScope, { DnsWire.servfail(it) }, { sent.add(it) })
        proxy.handle(syn()); proxy.handle(syn())
        assertArrayEquals(sent[0], sent[1])
        assertEquals(0L, parse(sent[0]).acknowledgement)
    }
    @Test fun `duplicate client FIN is acknowledged without double increment`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val pending = CompletableDeferred<ByteArray>()
        val proxy = DnsTcpProxy(backgroundScope, { pending.await() }, { sent.add(it) })
        val client = syn(); proxy.handle(client)
        val server = (parse(sent.last()).sequence + 1) and 0xffff_ffffL
        val ack = client.copy(sequence = 0, acknowledgement = server, flags = DnsTcpPacket.ACK)
        val data = frame(DnsWire.healthQuery())
        proxy.handle(ack.copy(payload = data, flags = DnsTcpPacket.ACK or DnsTcpPacket.FIN))
        val fin = ack.copy(sequence = data.size.toLong(), flags = DnsTcpPacket.ACK or DnsTcpPacket.FIN)
        val expected = parse(sent.last()).acknowledgement
        val before = sent.size
        proxy.handle(fin)
        assertEquals(before + 1, sent.size)
        assertEquals(expected, parse(sent.last()).acknowledgement)
    }
    @Test fun `asynchronous TUN write error is reported instead of escaping coroutine`() = runTest {
        val sent = mutableListOf<ByteArray>(); val errors = mutableListOf<Exception>()
        var fail = false
        val proxy = DnsTcpProxy(backgroundScope, { DnsWire.servfail(it) }, {
            if (fail) throw IOException("test TUN closed") else sent.add(it)
        }, { errors.add(it) })
        val client = syn(); proxy.handle(client)
        val ack = client.copy(sequence = 0, acknowledgement = (parse(sent.last()).sequence + 1) and 0xffff_ffffL, flags = DnsTcpPacket.ACK)
        proxy.handle(ack.copy(payload = frame(DnsWire.healthQuery())))
        fail = true; runCurrent()
        assertEquals(1, errors.size)
    }
    @Test fun `IPv6 fragmented application payload resolves once and RST discards late result`() = runTest {
        val sent = mutableListOf<ByteArray>(); val pending = CompletableDeferred<ByteArray>(); var calls = 0
        val proxy = DnsTcpProxy(backgroundScope, { calls++; pending.await() }, { sent.add(it) })
        val client = syn(6); proxy.handle(client)
        val ack = client.copy(sequence = 0, acknowledgement = (parse(sent.last()).sequence + 1) and 0xffff_ffffL, flags = DnsTcpPacket.ACK)
        val query = DnsWire.healthQuery(); val data = frame(query)
        proxy.handle(ack.copy(payload = data.copyOfRange(0, 1)))
        proxy.handle(ack.copy(sequence = 1, payload = data.copyOfRange(1, data.size)))
        runCurrent(); assertEquals(1, calls)
        proxy.handle(ack.copy(sequence = 1, payload = data.copyOfRange(1, data.size)))
        assertEquals(data.size.toLong(), parse(sent.last()).acknowledgement)
        proxy.handle(ack.copy(flags = DnsTcpPacket.RST))
        val before = sent.size; pending.complete(DnsWire.servfail(query)); runCurrent()
        assertEquals(before, sent.size)
    }
    @Test fun `session cap and invalid DNS frame length are enforced`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val proxy = DnsTcpProxy(backgroundScope, { error("must not resolve") }, { sent.add(it) })
        repeat(65) { proxy.handle(syn(port = 42000 + it)) }
        assertEquals(64, sent.size)
        val client = syn(); val server = (parse(sent.first()).sequence + 1) and 0xffff_ffffL
        proxy.handle(client.copy(sequence = 0, acknowledgement = server, flags = DnsTcpPacket.ACK, payload = byteArrayOf(0, 1, 0)))
        assertTrue(parse(sent.last()).flags and DnsTcpPacket.RST != 0)
    }
    @Test fun `packet parsers reject truncated and deterministic malformed packets without exceptions`() {
        val random = java.util.Random(42)
        repeat(10000) {
            val bytes = ByteArray(random.nextInt(800)); random.nextBytes(bytes)
            DnsTcpPacket.parse(bytes, bytes.size); DnsUdpPacket.parse(bytes, bytes.size)
        }
        for (version in listOf(4, 6)) {
            val bytes = DnsTcpPacket.reply(syn(version), 0xffff_ffffL, 0, DnsTcpPacket.ACK, byteArrayOf(1, 2, 3))
            for (n in 0 until bytes.size) assertNull(DnsTcpPacket.parse(bytes, n))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            assertNull(DnsTcpPacket.parse(bytes, bytes.size))
        }
    }
}
