package org.dnsbunker.android.core

import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TunHealthProbe {
    private val ports = ConcurrentHashMap.newKeySet<Int>()
    fun isHealthPort(port: Int) = port in ports
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout=5000
                ports.add(socket.localPort)
                try {
                    val query=DnsWire.healthQuery()
                    socket.connect(InetAddress.getByName("198.18.0.1"),53)
                    socket.send(DatagramPacket(query,query.size))
                    val response=DatagramPacket(ByteArray(4096),4096);socket.receive(response)
                    DnsWire.healthyResponse(query,response.data.copyOf(response.length))
                } finally { ports.remove(socket.localPort) }
            }
        } catch (_: java.io.IOException) { false }
    }
}
