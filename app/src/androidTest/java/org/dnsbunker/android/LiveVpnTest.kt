package org.dnsbunker.android

import android.net.VpnService
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.dnsbunker.android.core.*
import org.dnsbunker.android.vpn.DnsVpnService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.*
import org.junit.runner.RunWith
import java.net.*
import java.io.*

@RunWith(AndroidJUnit4::class)
class LiveVpnTest : LiveDeviceFixture() {
    private fun query(name: String, type: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x42, 0x11, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { out.write(it.length); out.write(it.toByteArray(Charsets.US_ASCII)) }
        out.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
        return out.toByteArray()
    }
    private fun udp(address: String, q: ByteArray): ByteArray = DatagramSocket().use { socket ->
        socket.soTimeout = 30000; socket.connect(InetAddress.getByName(address), 53)
        socket.send(DatagramPacket(q, q.size))
        val packet = DatagramPacket(ByteArray(4096), 4096); socket.receive(packet)
        packet.data.copyOf(packet.length).also { assertTrue(DnsWire.validResponse(q, it)) }
    }
    private fun checkUdp(address: String) {
        for (type in listOf(1, 28)) {
            val answer = udp(address, query("example.com", type))
            assertEquals("UDP $address type $type must resolve", 0, answer[3].toInt() and 15)
        }
    }
    private fun checkTcp(address: String) {
        for (type in listOf(1, 28)) {
            val q = query("example.com", type)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, 53), 5000); socket.soTimeout = 30000
                DataOutputStream(socket.getOutputStream()).apply { writeShort(q.size); write(q); flush() }
                val input = DataInputStream(socket.getInputStream())
                val answer = ByteArray(input.readUnsignedShort()); input.readFully(answer)
                assertTrue("TCP $address type $type", DnsWire.validResponse(q, answer))
                assertEquals(0, answer[3].toInt() and 15)
            }
        }
    }
    @Test fun resolverRpzMarkerProducesProStatisticsWithoutCountingOrdinaryNxdomain() {
        val before=AppState.snapshot.value.blockedPro
        val response=udp("198.18.0.1",query("pagead2.googlesyndication.com"))
        assertEquals(DnsWire.BlockCategory.PRO,DnsWire.blockCategory(response))
        assertTrue(AppState.snapshot.value.blockedPro>=before+1)
        val noData=udp("198.18.0.1",query("dnsbunker-regression-nxdomain.example.com"))
        assertEquals(DnsWire.BlockCategory.NONE,DnsWire.blockCategory(noData))
        val normal=udp("198.18.0.1",query("nx-regression-20261123.iana.org"))
        assertEquals(3,DnsWire.responseCode(normal));assertEquals(DnsWire.BlockCategory.NONE,DnsWire.blockCategory(normal))
    }
    @Test fun ipv4UdpAandAAAA() = checkUdp("198.18.0.1")
    @Test fun ipv6UdpAandAAAA() = checkUdp("fd77:db4b::1")
    @Test fun ipv4TcpAandAAAA() = checkTcp("198.18.0.1")
    @Test fun ipv6TcpAandAAAA() = checkTcp("fd77:db4b::1")
    @Test fun localNamesFailClosedOnBothIpVersions() {
        for (address in listOf("198.18.0.1", "fd77:db4b::1")) {
            assertEquals(2, udp(address, query("dnsbunker-regression.lan"))[3].toInt() and 15)
        }
    }
    @Test fun protectionBecomesActiveOnlyAfterEncryptedProbe() {
        val deadline = System.nanoTime() + 40_000_000_000L
        while (AppState.snapshot.value.status != ProtectionStatus.ACTIVE && System.nanoTime() < deadline) Thread.sleep(200)
        assertEquals(ProtectionStatus.ACTIVE, AppState.snapshot.value.status)
        assertTrue(listOf("DoH/2","DoH/3","DoQ","DoT").any {AppState.snapshot.value.transport.startsWith(it)})
    }
}
